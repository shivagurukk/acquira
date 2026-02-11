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

-- Staging
DROP TABLE IF EXISTS stg_merchant_master_raw CASCADE;
DROP TABLE IF EXISTS stg_trnx_raw CASCADE;

-- ==========================================
-- 1. Tenants, Regions, and RBAC Schema
-- ==========================================

-- 1.1 Regions (Optional grouping)
CREATE TABLE IF NOT EXISTS region (
    region_id SERIAL PRIMARY KEY,
    region_name VARCHAR(100) UNIQUE NOT NULL
);

-- 1.1b Reference Data: Countries & Currencies
CREATE TABLE IF NOT EXISTS ref_country (
    country_code VARCHAR(2) PRIMARY KEY, -- ISO 3166-1 alpha-2 (e.g., US, IN, BH)
    country_name VARCHAR(100) NOT NULL,
    currency_code VARCHAR(3) NOT NULL, -- ISO 4217 (e.g., USD, INR, BHD)
    currency_name VARCHAR(50),
    currency_symbol VARCHAR(5),
    phone_code VARCHAR(10)
);
-- ==================================================================================
-- PHASE 1.5: MULTI-TENANCY FOUNDATION
-- ==================================================================================

-- Secure Function to get current tenant from Session Variable (Simplified for Spring Data compat)
CREATE OR REPLACE FUNCTION get_current_tenant() RETURNS BIGINT AS '
    SELECT CAST(NULLIF(current_setting(''app.current_tenant'', true), '''') AS BIGINT);
'
LANGUAGE sql SECURITY DEFINER;


-- ==================================================================================
-- PHASE 2.5: DYNAMIC RBAC (Groups & Menus)
-- ==================================================================================

CREATE TABLE IF NOT EXISTS sys_user_group (
    group_id BIGSERIAL PRIMARY KEY,
    group_name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS sys_menu (
    menu_id BIGSERIAL PRIMARY KEY,
    menu_name VARCHAR(50) NOT NULL,
    path VARCHAR(100), -- '/dashboard', '/users'
    icon_key VARCHAR(50), -- 'LayoutDashboard', 'Users'
    category VARCHAR(50), -- 'EXECUTIVE', 'ADMINISTRATION'
    display_order INT,
    CONSTRAINT uq_menu_path UNIQUE (path)
);

CREATE TABLE IF NOT EXISTS sys_group_menu (
    group_id BIGINT REFERENCES sys_user_group(group_id),
    menu_id BIGINT REFERENCES sys_menu(menu_id),
    PRIMARY KEY (group_id, menu_id)
);

-- Update Users Table
-- ALTER TABLE users ADD COLUMN group_id BIGINT REFERENCES sys_user_group(group_id); -- Moved to UserTenantAccess

-- ==================================================================================
-- DATA INITIALIZATION (RBAC)
-- ==================================================================================

-- 1. Insert Default Groups
INSERT INTO sys_user_group (group_name, description) VALUES 
('Super Admin', 'Full Access to System'),
('Bank Admin', 'Bank Level Administration'),
('Business User', 'Access to Sales and Executive Dashboards'),
('Finance User', 'Access to Profitability and Settlements'),
('Ops User', 'Access to Batch Logs and Uploads')
ON CONFLICT (group_name) DO NOTHING;

-- 2. Insert Menus
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Dashboard', '/dashboard', 'LayoutDashboard', 'EXECUTIVE', 1),
('Sales Analytics', '/sales/analytics', 'TrendingUp', 'SALES', 2),
('Zero Sales', '/sales/zero-sales', 'AlertTriangle', 'SALES', 3),
('Profitability', '/finance/profitability', 'PieChart', 'FINANCE', 4),
('P&L Views', '/finance/pnl', 'DollarSign', 'FINANCE', 5),
('Batch Logs', '/ops/batch-logs', 'Activity', 'OPERATIONS', 6),
('Upload Files', '/upload', 'Upload', 'OPERATIONS', 7),
('User Management', '/users', 'Users', 'ADMINISTRATION', 8),
('Bank Setup', '/tenants', 'Building', 'ADMINISTRATION', 9),
('Group Management', '/admin/groups', 'Shield', 'ADMINISTRATION', 10),
('Merchant Universe', '/merchants', 'Store', 'MERCHANT MGT', 1),
('Transactions', '/transactions', 'List', 'MERCHANT MGT', 2),
('Merchant Summary', '/merchant-summary', 'Table', 'MERCHANT MGT', 3),
('Volume & Revenue', '/business/volume-revenue', 'BarChart2', 'BUSINESS', 4),
('Merchant Financial', '/business/merchant-financial', 'DollarSign', 'BUSINESS', 5),
('Performance Trends', '/business/performance', 'TrendingUp', 'BUSINESS', 6),
('Debit & Prepaid Metrics', '/business/debit-prepaid', 'CreditCard', 'BUSINESS', 7),
('Attrition Report', '/business/attrition', 'TrendingDown', 'BUSINESS', 8),
('Merchant Growth Heatmap', '/business/heatmap', 'Grid', 'BUSINESS', 9),
('Daily Merchant Dashboard', '/business/daily-dashboard', 'Calendar', 'BUSINESS', 10),
('Backup & Restore', '/admin/backups', 'Database', 'ADMINISTRATION', 11)
ON CONFLICT (path) DO NOTHING;

-- Map New Menu to Groups (Super Admin & Bank Admin)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin', 'Business User')
  AND (m.category = 'MERCHANT MGT' OR m.category = 'BUSINESS')
ON CONFLICT DO NOTHING;


-- 3. Map Menus to Groups (simplified for intial setup)
-- Super Admin (Group 1) gets everything
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT 1, menu_id FROM sys_menu
ON CONFLICT DO NOTHING;

-- 1.2 Tenant (The Core Institution/Bank Unit)
-- Replacing dim_institution concept with a robust Tenant master
-- Insert Finance Summary Menu
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Finance Summary', '/finance/summary', 'BookOpen', 'FINANCE', 2),
('Merchant Insight Hub', '/merchant/insight-hub', 'PieChart', 'MERCHANT MGT', 4),
('Transaction Trends', '/trends/hub', 'Activity', 'MERCHANT MGT', 5)
ON CONFLICT (path) DO NOTHING;

-- Map to Super Admin, Bank Admin, and Finance User
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin', 'Finance User', 'Ops User', 'Business User')
  AND m.menu_name IN ('Finance Summary', 'Merchant Insight Hub', 'Transaction Trends')
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS tenant (
    tenant_id SERIAL PRIMARY KEY,
    institution_id VARCHAR(50) UNIQUE NOT NULL, -- Logical ID (e.g., "BANK001")
    bank_name VARCHAR(100) NOT NULL,
    bank_short_code VARCHAR(10) UNIQUE NOT NULL, -- Used in file names, etc.
    base_currency VARCHAR(10) DEFAULT 'USD',
    country VARCHAR(100),
    currency_name VARCHAR(50),
    currency_symbol VARCHAR(10),
    region_id INT REFERENCES region(region_id), -- Added for Multi-Region
    status VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, INACTIVE
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 1.3 Roles
CREATE TABLE IF NOT EXISTS role (
    role_id SERIAL PRIMARY KEY,
    role_name VARCHAR(50) UNIQUE NOT NULL -- ROLE_ADMIN, ROLE_BANK_USER, ROLE_BUSINESS_USER, ROLE_FINANCE_USER
);

-- 1.4 Application Users
-- 1.4 Application Users
CREATE TABLE IF NOT EXISTS users (
    user_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(100),
    role VARCHAR(50), -- Added to support direct role mapping
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP
);

-- 1.5 User -> Role Mapping
CREATE TABLE IF NOT EXISTS user_role (
    map_id SERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
    role_id INT REFERENCES role(role_id) ON DELETE CASCADE,
    UNIQUE(user_id, role_id)
);

-- 1.6 User -> Tenant Access (CRITICAL for Multi-Tenancy)
CREATE TABLE IF NOT EXISTS user_tenant_access (
    access_id SERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
    tenant_id INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    group_id BIGINT REFERENCES sys_user_group(group_id),
    UNIQUE(user_id, tenant_id)
);

-- 1.7 Audit Log (Multi-Tenant)
CREATE TABLE IF NOT EXISTS audit_log (
    log_id BIGSERIAL PRIMARY KEY,
    tenant_id INT REFERENCES tenant(tenant_id), -- Nullable for System-level actions
    user_id BIGINT REFERENCES users(user_id),
    action_type VARCHAR(50), -- LOGIN, EXPORT, BATCH_RUN
    details TEXT,
    ip_address VARCHAR(45),
    event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 1.8 Tenant Settings (Phase 6)
CREATE TABLE IF NOT EXISTS tenant_setting (
    setting_id SERIAL PRIMARY KEY,
    tenant_id INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    setting_key VARCHAR(100) NOT NULL, -- e.g. 'vat_rate', 'weekend_days'
    setting_value TEXT,
    setting_type VARCHAR(20) DEFAULT 'STRING', -- STRING, JSON, BOOLEAN, NUMBER
    UNIQUE(tenant_id, setting_key)
);

-- 1.9 Dashboard Configuration (Phase 6)
CREATE TABLE IF NOT EXISTS dashboard_config (
    config_id SERIAL PRIMARY KEY,
    tenant_id INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    kpi_key VARCHAR(50) NOT NULL, -- e.g. 'total_volume'
    display_label VARCHAR(100),
    is_visible BOOLEAN DEFAULT TRUE,
    display_order INT,
    UNIQUE(tenant_id, kpi_key)
);

-- ==========================================
-- 2. Staging Tables (Raw Ingestion)
-- ==========================================
-- Staging tables are transient but we should tag them with Tenant for safety

CREATE TABLE IF NOT EXISTS stg_merchant_master_raw (
    raw_id BIGSERIAL PRIMARY KEY,
    tenant_id INT, -- Tagged during ingest
    file_id BIGINT,
    load_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    row_hash VARCHAR(64),
    status VARCHAR(20) DEFAULT 'PENDING',
    error_message TEXT,
    
    -- Columns from Excel
    institution_code VARCHAR(50),
    institution_name VARCHAR(100),
    entity_internal_id VARCHAR(50),
    entity_name VARCHAR(100),
    entity_code VARCHAR(50),
    aggregator_internal_id VARCHAR(50),
    aggregator_name VARCHAR(100),
    aggregator_code VARCHAR(50),
    merchant_internal_id VARCHAR(50),
    mid VARCHAR(50),
    merchant_name VARCHAR(150),
    merchant_status VARCHAR(50),
    merchant_store_internal_id VARCHAR(50),
    sid VARCHAR(50),
    store_legal_name VARCHAR(150),
    store_name VARCHAR(150),
    store_status VARCHAR(50),
    business_type VARCHAR(100),
    business_mcc VARCHAR(10),
    vat_number VARCHAR(50),
    primary_contact_person VARCHAR(100),
    primary_contact_number VARCHAR(50),
    primary_contact_email VARCHAR(100),
    primary_contact_designation VARCHAR(100),
    secondary_contact_person VARCHAR(100),
    secondary_contact_email VARCHAR(100),
    secondary_contact_number VARCHAR(50),
    secondary_contact_designation VARCHAR(100),
    address TEXT,
    city VARCHAR(100),
    state VARCHAR(100),
    postal_code VARCHAR(20),
    store_desc TEXT,
    industry_type VARCHAR(100),
    customer_type VARCHAR(100),
    source_of_fund VARCHAR(100),
    expected_volume DECIMAL(19, 2),
    regulated_activity BOOLEAN,
    regulated_activity_desc TEXT,
    auditor_name VARCHAR(100),
    is_pep BOOLEAN,
    pep_reason TEXT,
    high_risk_adverse_media BOOLEAN,
    high_risk_source_of_wealth BOOLEAN,
    risk_level VARCHAR(20),
    risk_level_high BOOLEAN,
    risk_level_prohibited BOOLEAN,
    risk_level_restricted BOOLEAN,
    product VARCHAR(100),
    date_of_onboarding TIMESTAMP,
    reviewed_date TIMESTAMP,
    next_reviewed_date TIMESTAMP,
    sales_user_email VARCHAR(100),
    sales_user_id VARCHAR(50),
    referral_partner VARCHAR(100),
    created_date TIMESTAMP,
    terminal_internal_id VARCHAR(50),
    tid VARCHAR(50),
    terminal_name VARCHAR(100),
    terminal_status VARCHAR(50),
    terminal_device_number VARCHAR(50),
    terminal_type VARCHAR(50),
    terminal_description TEXT,
    bank_name VARCHAR(100),
    bank_account_name VARCHAR(100),
    bank_account_number VARCHAR(50),
    swift_code VARCHAR(50),
    iban_number VARCHAR(50),
    merchant_created_date TIMESTAMP,
    merchant_store_created_date TIMESTAMP,
    terminal_created_date TIMESTAMP
);

CREATE TABLE IF NOT EXISTS stg_trnx_raw (
    raw_id BIGSERIAL PRIMARY KEY,
    tenant_id INT, -- Tagged during ingest
    file_id BIGINT,
    load_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    row_hash VARCHAR(64),
    status VARCHAR(20) DEFAULT 'PENDING',
    error_message TEXT,
    
    entity_name VARCHAR(100),
    aggregator_internal_id VARCHAR(50),
    aggregator_name VARCHAR(100),
    aggregator_code VARCHAR(50),
    mid VARCHAR(50),
    merchant_internal_id VARCHAR(50),
    merchant_name VARCHAR(150),
    sid VARCHAR(50),
    merchant_store_internal_id VARCHAR(50),
    cmm_merchant_store_internal_id VARCHAR(50),
    merchant_store_legal_name VARCHAR(150),
    store_name VARCHAR(150),
    tid VARCHAR(50),
    arn VARCHAR(100),
    rrn_number VARCHAR(100),
    card_number VARCHAR(50), 
    auth_code VARCHAR(50),
    payment_date TIMESTAMP,
    transaction_date TIMESTAMP,
    batch_number VARCHAR(50),
    transaction_type VARCHAR(50),
    card_scheme VARCHAR(50),
    card_type VARCHAR(50),
    dcc BOOLEAN,
    txn_currency VARCHAR(10),
    txn_currency_amount DECIMAL(19, 2),
    store_base_currency VARCHAR(10),
    store_base_currency_amount DECIMAL(19, 2),
    msf DECIMAL(19, 4),
    vat DECIMAL(19, 4),
    total_amount_settled DECIMAL(19, 2),
    interchange_fee DECIMAL(19, 4),
    destination VARCHAR(50)
);
ALTER TABLE stg_merchant_master_raw ENABLE ROW LEVEL SECURITY;
ALTER TABLE stg_trnx_raw ENABLE ROW LEVEL SECURITY;

-- ==========================================
-- 3. Operational Tables (RLS Protected)
-- ==========================================

CREATE TABLE IF NOT EXISTS dim_merchant (
    merchant_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id), -- RLS Key
    
    internal_id VARCHAR(50),
    mid VARCHAR(50),
    name VARCHAR(150),
    status VARCHAR(50),
    created_date TIMESTAMP,
    sales_user_id VARCHAR(50),
    sales_email VARCHAR(100), -- For RM Mapping
    referral_partner VARCHAR(100),
    risk_level VARCHAR(20),

    industry VARCHAR(100),
    mcc VARCHAR(10),
    location VARCHAR(100),
    city VARCHAR(100),
    
    UNIQUE(tenant_id, internal_id) -- Duplicate IDs allowed across different tenants
);

-- Enable RLS for dim_merchant
ALTER TABLE dim_merchant ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS dim_store (
    store_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id), -- RLS Key
    
    internal_id VARCHAR(50),
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    sid VARCHAR(50),
    name VARCHAR(150),
    legal_name VARCHAR(150),
    address TEXT,
    city VARCHAR(100),
    state VARCHAR(100),
    postal_code VARCHAR(50),
    mcc VARCHAR(10),
    status VARCHAR(50),
    created_date TIMESTAMP,

    -- Location & Geo
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    timezone VARCHAR(50),
    operating_hours JSONB,
    
    UNIQUE(tenant_id, internal_id)
);
ALTER TABLE dim_store ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS dim_terminal (
    terminal_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id), -- RLS Key
    
    internal_id VARCHAR(50),
    store_id BIGINT REFERENCES dim_store(store_id),
    tid VARCHAR(50),
    device_number VARCHAR(50),
    type VARCHAR(50),
    status VARCHAR(50),
    created_date TIMESTAMP,
    
    UNIQUE(tenant_id, internal_id)
);
ALTER TABLE dim_terminal ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS dim_bank_account (
    account_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id), -- RLS Key
    
    store_id BIGINT REFERENCES dim_store(store_id),
    bank_name VARCHAR(100),
    account_number VARCHAR(50),
    swift_code VARCHAR(50),
    iban VARCHAR(50)
);
ALTER TABLE dim_bank_account ENABLE ROW LEVEL SECURITY;

-- ==========================================
-- 4. Advanced Features (RLS Protected)
-- ==========================================

CREATE TABLE IF NOT EXISTS bank_budget_target (
    budget_id SERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id), -- RLS Key
    
    month_key INT, 
    metric_type VARCHAR(50),
    target_value DECIMAL(19, 2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE bank_budget_target ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS merchant_lifecycle_status (
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    current_status VARCHAR(50),
    reason_code VARCHAR(50),
    last_status_change TIMESTAMP
);
ALTER TABLE merchant_lifecycle_status ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS merchant_activity_summary (
    summary_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    calc_date DATE NOT NULL,
    
    first_txn_date TIMESTAMP,
    last_txn_date TIMESTAMP,
    
    last_7d_cnt INT DEFAULT 0,
    last_7d_value DECIMAL(19, 2) DEFAULT 0,
    last_30d_cnt INT DEFAULT 0,
    last_30d_value DECIMAL(19, 2) DEFAULT 0,
    
    status VARCHAR(50),
    status_change_date TIMESTAMP,
    
    UNIQUE(tenant_id, merchant_id, calc_date)
);
ALTER TABLE merchant_activity_summary ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS merchant_opportunity_score (
    score_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    
    score DECIMAL(5, 2),
    reason_tags VARCHAR(255),
    calc_date DATE,
    
    UNIQUE(tenant_id, merchant_id, calc_date)
);
ALTER TABLE merchant_opportunity_score ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS revenue_leakage_flags (
    flag_id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    check_type VARCHAR(50),
    severity VARCHAR(20),
    details TEXT,
    detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_resolved BOOLEAN DEFAULT FALSE
);
ALTER TABLE revenue_leakage_flags ENABLE ROW LEVEL SECURITY;

-- ==========================================
-- 5. Comprehensive Merchant Management Tables
-- ==========================================

CREATE TABLE IF NOT EXISTS merchant_contact (
    contact_id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    store_id BIGINT REFERENCES dim_store(store_id), -- Optional link to specific store
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    
    contact_name VARCHAR(150),
    role VARCHAR(50), -- 'Primary', 'Technical', 'Finance', 'Emergency'
    email VARCHAR(150),
    phone VARCHAR(50),
    is_primary BOOLEAN DEFAULT FALSE
);
ALTER TABLE merchant_contact ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS merchant_document (
    document_id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    
    document_type VARCHAR(50), -- 'Agreement', 'KYC', 'License'
    document_name VARCHAR(150),
    file_path VARCHAR(255),
    upload_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expiry_date TIMESTAMP
);
ALTER TABLE merchant_document ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS merchant_contract (
    contract_id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    
    contract_number VARCHAR(100),
    start_date DATE,
    end_date DATE,
    auto_renew BOOLEAN DEFAULT FALSE,
    status VARCHAR(50) -- 'Active', 'Expired', 'Pending'
);
ALTER TABLE merchant_contract ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS merchant_risk_profile (
    profile_id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    
    risk_score INT,
    compliance_status VARCHAR(50), -- 'Compliant', 'Under Review'
    kyc_status VARCHAR(50),
    aml_checks_passed BOOLEAN,
    last_review_date DATE,
    notes TEXT
);
ALTER TABLE merchant_risk_profile ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS merchant_settlement_config (
    config_id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    
    settlement_frequency VARCHAR(50), -- 'Daily', 'Weekly'
    hold_days INT DEFAULT 0,
    min_settlement_amount DECIMAL(19, 2),
    currency VARCHAR(10)
);
ALTER TABLE merchant_settlement_config ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS merchant_note (
    note_id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    
    note_text TEXT,
    created_by VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE merchant_note ENABLE ROW LEVEL SECURITY;

-- ==========================================
-- 5. Helper Tables (Global or RLS)
-- ==========================================
CREATE TABLE IF NOT EXISTS dim_aggregator (
    aggregator_id SERIAL PRIMARY KEY,
    tenant_id INT REFERENCES tenant(tenant_id), -- Optional owner
    internal_id VARCHAR(50),
    name VARCHAR(100),
    code VARCHAR(50)
);

-- ==========================================
-- 6. Main Fact Tables (RLS Protected)
-- ==========================================

CREATE TABLE IF NOT EXISTS fact_transaction (
    transaction_id BIGSERIAL,
    tenant_id INT NOT NULL, -- references tenant(tenant_id) commented out to allow partitioning without complex FK handling across partitions
    
    merchant_id BIGINT, 
    store_id BIGINT,
    terminal_id BIGINT,
    
    arn VARCHAR(100),
    rrn_number VARCHAR(100),
    card_number VARCHAR(50), 
    auth_code VARCHAR(50),
    
    payment_date TIMESTAMP NOT NULL, -- Required for partitioning
    transaction_date TIMESTAMP,
    batch_number VARCHAR(50),
    transaction_type VARCHAR(50),
    card_scheme VARCHAR(50),
    card_type VARCHAR(50),
    dcc BOOLEAN,
    
    txn_currency VARCHAR(10),
    txn_currency_amount DECIMAL(19, 2),
    store_base_currency VARCHAR(10),
    store_base_currency_amount DECIMAL(19, 2),
    
    msf DECIMAL(19, 4),
    vat DECIMAL(19, 4),
    total_amount_settled DECIMAL(19, 2),
    interchange_fee DECIMAL(19, 4),
    destination VARCHAR(50),
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    PRIMARY KEY (transaction_id, payment_date)
) PARTITION BY RANGE (payment_date);

-- Initial Partitions
CREATE TABLE IF NOT EXISTS fact_transaction_y2024 PARTITION OF fact_transaction FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS fact_transaction_y2025 PARTITION OF fact_transaction FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS fact_transaction_default PARTITION OF fact_transaction DEFAULT;

-- Enable RLS
ALTER TABLE fact_transaction ENABLE ROW LEVEL SECURITY;

-- ==========================================
-- 7. Summary Tables (RLS Protected)
-- ==========================================

-- Clean up old non-partitioned tables to allow re-creation as partitioned tables
DROP TABLE IF EXISTS sum_daily_merchant CASCADE;
DROP TABLE IF EXISTS sum_daily_scheme CASCADE;
DROP TABLE IF EXISTS sum_daily_channel CASCADE;
DROP TABLE IF EXISTS sum_daily_terminal CASCADE;
DROP TABLE IF EXISTS sum_daily_bank CASCADE;
DROP TABLE IF EXISTS sum_daily_finance CASCADE;
DROP TABLE IF EXISTS sum_daily_insight CASCADE;

CREATE TABLE IF NOT EXISTS sum_daily_merchant (
    summary_id BIGSERIAL, -- No default PK here for partitioning
    tenant_id INT NOT NULL, -- FK constraint not supported on partitioned tables to foreign tables easily in all versions, removing FK for strict partition support or keep if PG12+ compatible
    
    business_date DATE NOT NULL,
    institution_id INT, 
    merchant_id BIGINT,
    store_id BIGINT,
    
    total_txns INT,
    total_volume DECIMAL(19, 2),
    total_msf DECIMAL(19, 2),
    total_interchange DECIMAL(19, 2),
    total_scheme_fee DECIMAL(19, 2), 
    total_margin DECIMAL(19, 2),
    
    total_debit_prepaid_volume DECIMAL(19, 2) DEFAULT 0,
    total_credit_volume DECIMAL(19, 2) DEFAULT 0,
    sales_user_id VARCHAR(50),
    
    unique_customer_count BIGINT DEFAULT 0,
    top_spending_customer_id VARCHAR(50),
    top_spending_amount DECIMAL(19, 2),
    
    -- DCC Metrics
    dcc_eligible_volume DECIMAL(19, 2) DEFAULT 0,
    dcc_optin_volume DECIMAL(19, 2) DEFAULT 0,
    dcc_optout_volume DECIMAL(19, 2) DEFAULT 0,
    dcc_eligible_count BIGINT DEFAULT 0,
    dcc_optin_count BIGINT DEFAULT 0,
    
    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id)
) PARTITION BY RANGE (business_date);

-- Partitions
CREATE TABLE IF NOT EXISTS sum_daily_merchant_y2024 PARTITION OF sum_daily_merchant
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_y2025 PARTITION OF sum_daily_merchant
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_y2026 PARTITION OF sum_daily_merchant
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_default PARTITION OF sum_daily_merchant DEFAULT;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_sum_merch_tenant_date ON sum_daily_merchant (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_merch_id ON sum_daily_merchant (merchant_id);
-- Row Level Security
ALTER TABLE sum_daily_merchant ENABLE ROW LEVEL SECURITY;


CREATE TABLE IF NOT EXISTS sum_daily_scheme (
    summary_id BIGSERIAL,
    tenant_id INT NOT NULL,
    business_date DATE NOT NULL,
    card_scheme VARCHAR(50),
    
    total_txns BIGINT DEFAULT 0,
    total_volume DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(19, 2) DEFAULT 0,
    total_interchange DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee DECIMAL(19, 2) DEFAULT 0,
    total_net_revenue DECIMAL(19, 2) DEFAULT 0,
    
    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, card_scheme)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_scheme_y2024 PARTITION OF sum_daily_scheme FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_scheme_y2025 PARTITION OF sum_daily_scheme FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_scheme_default PARTITION OF sum_daily_scheme DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_scheme_tenant_date ON sum_daily_scheme (tenant_id, business_date);
ALTER TABLE sum_daily_scheme ENABLE ROW LEVEL SECURITY;


CREATE TABLE IF NOT EXISTS sum_daily_channel (
    summary_id BIGSERIAL,
    tenant_id INT NOT NULL,
    business_date DATE NOT NULL,
    channel VARCHAR(50), 
    
    total_txns BIGINT DEFAULT 0,
    total_volume DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(19, 2) DEFAULT 0,
    total_interchange DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee DECIMAL(19, 2) DEFAULT 0,
    total_net_revenue DECIMAL(19, 2) DEFAULT 0,
    
    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, channel)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_channel_y2024 PARTITION OF sum_daily_channel FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_channel_y2025 PARTITION OF sum_daily_channel FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_channel_default PARTITION OF sum_daily_channel DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_channel_tenant_date ON sum_daily_channel (tenant_id, business_date);
ALTER TABLE sum_daily_channel ENABLE ROW LEVEL SECURITY;


CREATE TABLE IF NOT EXISTS sum_daily_terminal (
    summary_id BIGSERIAL,
    tenant_id INT NOT NULL,
    business_date DATE NOT NULL,
    merchant_id BIGINT,
    store_id BIGINT,
    terminal_id BIGINT,
    
    total_txns BIGINT DEFAULT 0,
    total_volume DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(19, 2) DEFAULT 0,
    total_revenue DECIMAL(19, 2) DEFAULT 0, 
    
    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_terminal_y2024 PARTITION OF sum_daily_terminal FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_terminal_y2025 PARTITION OF sum_daily_terminal FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_terminal_default PARTITION OF sum_daily_terminal DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_term_tenant_date ON sum_daily_terminal (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_term_ids ON sum_daily_terminal (merchant_id, terminal_id);
ALTER TABLE sum_daily_terminal ENABLE ROW LEVEL SECURITY;


CREATE TABLE IF NOT EXISTS sum_daily_merchant_attribute (
    id BIGSERIAL,
    merchant_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    attribute_type VARCHAR(50) NOT NULL,
    attribute_value VARCHAR(100) NOT NULL,
    
    metric_count BIGINT DEFAULT 0,
    metric_volume DECIMAL(19, 2) DEFAULT 0,
    version BIGINT,
    tenant_id INT NOT NULL,
    
    PRIMARY KEY (id, business_date),
    UNIQUE (tenant_id, merchant_id, business_date, attribute_type, attribute_value)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_merch_attr_y2024 PARTITION OF sum_daily_merchant_attribute FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merch_attr_y2025 PARTITION OF sum_daily_merchant_attribute FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merch_attr_y2026 PARTITION OF sum_daily_merchant_attribute FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merch_attr_default PARTITION OF sum_daily_merchant_attribute DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sdma_merchant_date ON sum_daily_merchant_attribute (merchant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sdma_attr_type ON sum_daily_merchant_attribute (attribute_type);
ALTER TABLE sum_daily_merchant_attribute ENABLE ROW LEVEL SECURITY;


CREATE TABLE IF NOT EXISTS sum_monthly_bank (
    summary_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    
    month_key INT NOT NULL,
    
    total_txns BIGINT,
    total_volume DECIMAL(19, 2),
    total_msf DECIMAL(19, 2),
    total_interchange DECIMAL(19, 2),
    total_scheme_fee DECIMAL(19, 2) DEFAULT 0,
    total_vat DECIMAL(19, 2),
    total_net_revenue DECIMAL(19, 2),
    
    UNIQUE(tenant_id, month_key)
);
ALTER TABLE sum_monthly_bank ENABLE ROW LEVEL SECURITY;


CREATE TABLE IF NOT EXISTS sum_daily_bank (
    summary_id BIGSERIAL,
    tenant_id INT NOT NULL,
    business_date DATE NOT NULL,
    
    total_txns BIGINT DEFAULT 0,
    total_volume DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(19, 2) DEFAULT 0,
    total_interchange DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee DECIMAL(19, 2) DEFAULT 0,
    total_vat DECIMAL(19, 2) DEFAULT 0,
    total_net_revenue DECIMAL(19, 2) DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_bank_y2024 PARTITION OF sum_daily_bank FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_bank_y2025 PARTITION OF sum_daily_bank FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_bank_default PARTITION OF sum_daily_bank DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_bank_tenant_date ON sum_daily_bank (tenant_id, business_date);
ALTER TABLE sum_daily_bank ENABLE ROW LEVEL SECURITY;


CREATE TABLE IF NOT EXISTS sum_daily_finance (
    summary_id BIGSERIAL,
    tenant_id INT NOT NULL,
    business_date DATE NOT NULL,
    
    -- Domestic Debit & Prepaid
    dom_debit_cnt BIGINT DEFAULT 0,
    dom_debit_vol DECIMAL(19, 2) DEFAULT 0,
    dom_debit_msf DECIMAL(19, 2) DEFAULT 0,
    dom_debit_optin DECIMAL(19, 2) DEFAULT 0, -- Opt-in Volume (DCC)

    -- Domestic Credit
    dom_credit_cnt BIGINT DEFAULT 0,
    dom_credit_vol DECIMAL(19, 2) DEFAULT 0,
    dom_credit_msf DECIMAL(19, 2) DEFAULT 0,
    dom_credit_optin DECIMAL(19, 2) DEFAULT 0,

    -- International
    int_cnt BIGINT DEFAULT 0,
    int_vol DECIMAL(19, 2) DEFAULT 0,
    int_msf DECIMAL(19, 2) DEFAULT 0,
    int_optin DECIMAL(19, 2) DEFAULT 0,

    -- Totals
    total_vol DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(19, 2) DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_finance_y2024 PARTITION OF sum_daily_finance FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_finance_y2025 PARTITION OF sum_daily_finance FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_finance_default PARTITION OF sum_daily_finance DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_fin_tenant_date ON sum_daily_finance (tenant_id, business_date);
ALTER TABLE sum_daily_finance ENABLE ROW LEVEL SECURITY;


CREATE TABLE IF NOT EXISTS sum_daily_insight (
    summary_id BIGSERIAL,
    tenant_id INT NOT NULL,
    business_date DATE NOT NULL,
    
    merchant_id BIGINT,
    store_id BIGINT,
    terminal_id BIGINT,
    
    card_scheme VARCHAR(50),
    card_type VARCHAR(50),
    destination VARCHAR(50),
    channel VARCHAR(50),
    is_opt_in BOOLEAN,
    
    total_txns BIGINT DEFAULT 0,
    total_volume DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(19, 2) DEFAULT 0,
    
    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_insight_y2024 PARTITION OF sum_daily_insight FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_insight_y2025 PARTITION OF sum_daily_insight FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_insight_default PARTITION OF sum_daily_insight DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_insight_tenant_date ON sum_daily_insight (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_insight_merchant ON sum_daily_insight (merchant_id);
ALTER TABLE sum_daily_insight ENABLE ROW LEVEL SECURITY;


CREATE TABLE IF NOT EXISTS sum_daily_mcc (
    summary_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    business_date DATE NOT NULL,
    mcc VARCHAR(10),
    card_scheme VARCHAR(50), 
    
    total_txns BIGINT DEFAULT 0,
    total_volume DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee DECIMAL(19, 2) DEFAULT 0, -- New
    total_net_revenue DECIMAL(19, 2) DEFAULT 0,
    
    UNIQUE(tenant_id, business_date, mcc, card_scheme)
);

CREATE TABLE IF NOT EXISTS merchant_activity (
    activity_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    
    last_txn_date DATE,
    days_since_last_txn INT,
    status VARCHAR(20), -- ACTIVE, DORMANT, CHURNED
    
    UNIQUE(tenant_id, merchant_id)
);

CREATE TABLE IF NOT EXISTS kpi_snapshot_daily (
    snapshot_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    snapshot_date DATE NOT NULL,
    
    metric_key VARCHAR(50), -- TOTAL_VOL, TOTAL_REV, ACTIVE_MERCHANTS, NEW_MERCHANTS
    metric_value DECIMAL(19, 2),
    
    UNIQUE(tenant_id, snapshot_date, metric_key)
);

CREATE TABLE IF NOT EXISTS kpi_snapshot_monthly (
    snapshot_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id),
    month_key INT NOT NULL, -- YYYYMM
    
    metric_key VARCHAR(50),
    metric_value DECIMAL(19, 2),
    
    UNIQUE(tenant_id, month_key, metric_key)
);

-- ==========================================
-- 9. Advanced RBAC
-- ==========================================

CREATE TABLE IF NOT EXISTS user_region_access (
    access_id SERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE,
    region_id INT REFERENCES region(region_id) ON DELETE CASCADE,
    UNIQUE(user_id, region_id)
);

CREATE TABLE IF NOT EXISTS batch_run_log (
    run_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT,
    job_name VARCHAR(100),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(20), -- COMPLETED, FAILED, RUNNING
    records_processed INT DEFAULT 0,
    records_failed INT DEFAULT 0,
    error_message TEXT
);

-- Sum Monthly Card (Optimized for Loyalty / Frequency)
CREATE TABLE IF NOT EXISTS sum_monthly_card (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    merchant_id BIGINT,
    month_key INT, -- YYYYMM
    card_number VARCHAR(50),
    
    visit_count INT DEFAULT 0,
    total_spend DECIMAL(19, 2) DEFAULT 0,
    UNIQUE(tenant_id, merchant_id, month_key, card_number)
);
CREATE INDEX IF NOT EXISTS idx_sum_card_merch_month ON sum_monthly_card (merchant_id, month_key);
ALTER TABLE sum_monthly_card ENABLE ROW LEVEL SECURITY;
-- ==========================================
-- 8. Advanced Merchant Metrics (Daily Dashboard)
-- ==========================================

CREATE TABLE IF NOT EXISTS sum_monthly_merchant_metrics (
    metric_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(tenant_id),
    merchant_id BIGINT REFERENCES dim_merchant(merchant_id),
    month_year VARCHAR(7) NOT NULL, -- Format: YYYY-MM
    
    -- Volatility & Risk
    volatility_index DECIMAL(10, 4),
    stability_label VARCHAR(50), -- 'Stable', 'Fluctuating', 'Unstable'
    behavior_tag VARCHAR(50),    -- 'Weekend Heavy', 'Payday Spikes', etc.
    smart_comment TEXT,
    
    -- Weekly Health (Green/Yellow/Red)
    week_1_health VARCHAR(20),
    week_2_health VARCHAR(20),
    week_3_health VARCHAR(20),
    week_4_health VARCHAR(20),
    week_5_health VARCHAR(20),
    
    -- Aggregate Stats for the Month
    total_volume DECIMAL(19, 2),
    avg_daily_volume DECIMAL(19, 2),
    max_daily_volume DECIMAL(19, 2),
    min_daily_volume DECIMAL(19, 2),
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE(tenant_id, merchant_id, month_year)
);
ALTER TABLE sum_monthly_merchant_metrics ENABLE ROW LEVEL SECURITY;

-- Migration V2: Hybrid Reporting Engine Tables
CREATE TABLE IF NOT EXISTS merchant_daily_metrics (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(tenant_id),
    report_date DATE NOT NULL,
    merchant_id VARCHAR(255) NOT NULL,
    merchant_name VARCHAR(255),
    mid VARCHAR(255),
    
    -- Volumes
    today_volume DOUBLE PRECISION DEFAULT 0.0,
    yesterday_volume DOUBLE PRECISION DEFAULT 0.0,
    avg7day DOUBLE PRECISION DEFAULT 0.0,
    total_mtd DOUBLE PRECISION DEFAULT 0.0,
    
    -- BI Metrics
    trend_pct DOUBLE PRECISION DEFAULT 0.0,
    volatility VARCHAR(20),
    risk_score INTEGER DEFAULT 0,
    ui_status VARCHAR(20),
    
    -- JSON Data
    daily_volumes_json TEXT,
    sparkline_data_json TEXT,
    
    -- Meta
    source_type VARCHAR(50),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE merchant_daily_metrics ENABLE ROW LEVEL SECURITY;

CREATE INDEX IF NOT EXISTS idx_metrics_date ON merchant_daily_metrics(report_date);
CREATE INDEX IF NOT EXISTS idx_metrics_mid ON merchant_daily_metrics(mid);
CREATE INDEX IF NOT EXISTS idx_metrics_merchant_id ON merchant_daily_metrics(merchant_id);


-- 2. Data Source Config (For External DB Connections)
CREATE TABLE IF NOT EXISTS data_source_config (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    db_type VARCHAR(50) NOT NULL, -- ORACLE, POSTGRES, MSSQL
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    db_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_password VARCHAR(255) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. Report Query Config (Stored SQL Queries)
CREATE TABLE IF NOT EXISTS report_query_config (
    id BIGSERIAL PRIMARY KEY,
    report_name VARCHAR(255) NOT NULL,
    sql_text TEXT NOT NULL,
    source_id BIGINT REFERENCES data_source_config(id),
    description TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    approved_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 4. Report Run Log (Audit Trail)
CREATE TABLE IF NOT EXISTS report_run_log (
    id BIGSERIAL PRIMARY KEY,
    query_id BIGINT REFERENCES report_query_config(id),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(50), -- SUCCESS, FAILED, RUNNING
    row_count INTEGER,
    error_message TEXT
);

-- Apply Tenant Isolation Policy to all RLS-enabled tables
-- We use a standardized policy name 'tenant_isolation_policy' across all tables.

-- Core & RBAC
DROP POLICY IF EXISTS tenant_isolation_policy ON tenant_setting;
CREATE POLICY tenant_isolation_policy ON tenant_setting USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON audit_log;
CREATE POLICY tenant_isolation_policy ON audit_log USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON dashboard_config;
CREATE POLICY tenant_isolation_policy ON dashboard_config USING (tenant_id = get_current_tenant());

-- Dimensions
DROP POLICY IF EXISTS tenant_isolation_policy ON dim_merchant;
CREATE POLICY tenant_isolation_policy ON dim_merchant USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON dim_store;
CREATE POLICY tenant_isolation_policy ON dim_store USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON dim_terminal;
CREATE POLICY tenant_isolation_policy ON dim_terminal USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON dim_bank_account;
CREATE POLICY tenant_isolation_policy ON dim_bank_account USING (tenant_id = get_current_tenant());

-- Business Advanced
DROP POLICY IF EXISTS tenant_isolation_policy ON bank_budget_target;
CREATE POLICY tenant_isolation_policy ON bank_budget_target USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_lifecycle_status;
CREATE POLICY tenant_isolation_policy ON merchant_lifecycle_status USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_activity_summary;
CREATE POLICY tenant_isolation_policy ON merchant_activity_summary USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_opportunity_score;
CREATE POLICY tenant_isolation_policy ON merchant_opportunity_score USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON revenue_leakage_flags;
CREATE POLICY tenant_isolation_policy ON revenue_leakage_flags USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_contact;
CREATE POLICY tenant_isolation_policy ON merchant_contact USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_document;
CREATE POLICY tenant_isolation_policy ON merchant_document USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_contract;
CREATE POLICY tenant_isolation_policy ON merchant_contract USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_risk_profile;
CREATE POLICY tenant_isolation_policy ON merchant_risk_profile USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_settlement_config;
CREATE POLICY tenant_isolation_policy ON merchant_settlement_config USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_note;
CREATE POLICY tenant_isolation_policy ON merchant_note USING (tenant_id = get_current_tenant());

-- Transactions & Summaries
DROP POLICY IF EXISTS tenant_isolation_policy ON fact_transaction;
CREATE POLICY tenant_isolation_policy ON fact_transaction USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_merchant;
CREATE POLICY tenant_isolation_policy ON sum_daily_merchant USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_scheme;
CREATE POLICY tenant_isolation_policy ON sum_daily_scheme USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_channel;
CREATE POLICY tenant_isolation_policy ON sum_daily_channel USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_terminal;
CREATE POLICY tenant_isolation_policy ON sum_daily_terminal USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_bank;
CREATE POLICY tenant_isolation_policy ON sum_daily_bank USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_finance;
CREATE POLICY tenant_isolation_policy ON sum_daily_finance USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_insight;
CREATE POLICY tenant_isolation_policy ON sum_daily_insight USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_mcc;
CREATE POLICY tenant_isolation_policy ON sum_daily_mcc USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_monthly_bank;
CREATE POLICY tenant_isolation_policy ON sum_monthly_bank USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_monthly_card;
CREATE POLICY tenant_isolation_policy ON sum_monthly_card USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_monthly_merchant_metrics;
CREATE POLICY tenant_isolation_policy ON sum_monthly_merchant_metrics USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_activity;
CREATE POLICY tenant_isolation_policy ON merchant_activity USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON kpi_snapshot_daily;
CREATE POLICY tenant_isolation_policy ON kpi_snapshot_daily USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON kpi_snapshot_monthly;
CREATE POLICY tenant_isolation_policy ON kpi_snapshot_monthly USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON batch_run_log;
CREATE POLICY tenant_isolation_policy ON batch_run_log USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_merchant_attribute;
CREATE POLICY tenant_isolation_policy ON sum_daily_merchant_attribute USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON stg_merchant_master_raw;
CREATE POLICY tenant_isolation_policy ON stg_merchant_master_raw USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON stg_trnx_raw;
CREATE POLICY tenant_isolation_policy ON stg_trnx_raw USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_daily_metrics;
CREATE POLICY tenant_isolation_policy ON merchant_daily_metrics USING (tenant_id = get_current_tenant());


-- ==================================================================================
-- 4. DEFAULT DATA (Tenant, Users, Access)
-- ==================================================================================

-- 4.1 Default Tenant
INSERT INTO tenant (institution_id, bank_name, bank_short_code, country, base_currency) 
VALUES ('BANK001', 'Acquira Bank', 'ACQ', 'Bahrain', 'BHD')
ON CONFLICT (institution_id) DO NOTHING;

-- 4.2 Default Users
-- Password is 'password' (BCrypt encoded: $2a$10$slYQmyNdGzTn7ZLBXBChFOC9f6kFjAqPhccnP6DxlTzceYkEGCcqi)
INSERT INTO users (username, password_hash, email, is_active) VALUES 
('admin', '{noop}password', 'admin@acquira.com', true)
ON CONFLICT (username) DO UPDATE 
SET password_hash = EXCLUDED.password_hash;

-- 4.3 Assign Access
-- Admin -> Acquira Bank -> Super Admin (Group 1)
INSERT INTO user_tenant_access (user_id, tenant_id, group_id)
SELECT u.user_id, t.tenant_id, g.group_id
FROM users u, tenant t, sys_user_group g
WHERE u.username = 'admin' AND t.institution_id = 'BANK001' AND g.group_name = 'Super Admin'
ON CONFLICT (user_id, tenant_id) DO NOTHING;

-- ==========================================
-- PERFORMANCE INDEXES — Critical for 999K+ transactions
-- ==========================================

-- fact_transaction: every summary query filters on (tenant_id, payment_date)
CREATE INDEX IF NOT EXISTS idx_fact_txn_tenant_date ON fact_transaction (tenant_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_fact_txn_tenant_merchant_date ON fact_transaction (tenant_id, merchant_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_fact_txn_merchant ON fact_transaction (merchant_id);
CREATE INDEX IF NOT EXISTS idx_fact_txn_card ON fact_transaction (card_number);

-- stg_trnx_raw: staging lookups during ingest pipeline
CREATE INDEX IF NOT EXISTS idx_stg_tenant ON stg_trnx_raw (tenant_id);
CREATE INDEX IF NOT EXISTS idx_stg_tenant_date ON stg_trnx_raw (tenant_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_stg_mid ON stg_trnx_raw (mid);

-- dim_merchant: JOIN target during staging→fact
CREATE INDEX IF NOT EXISTS idx_dim_merchant_mid ON dim_merchant (mid, tenant_id);

-- dim_store: JOIN target during staging→fact
CREATE INDEX IF NOT EXISTS idx_dim_store_merchant ON dim_store (merchant_id, tenant_id);
CREATE INDEX IF NOT EXISTS idx_dim_store_sid ON dim_store (sid, tenant_id);

-- dim_terminal: JOIN target during staging→fact
CREATE INDEX IF NOT EXISTS idx_dim_terminal_store ON dim_terminal (store_id, tenant_id);
CREATE INDEX IF NOT EXISTS idx_dim_terminal_tid ON dim_terminal (tid, tenant_id);

-- ==========================================
-- SAVED FILTERS / VIEWS
-- ==========================================
DROP TABLE IF EXISTS saved_filter CASCADE;

CREATE TABLE IF NOT EXISTS saved_filter (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       INT NOT NULL,
    user_id         BIGINT NOT NULL,
    name            VARCHAR(100) NOT NULL,
    dashboard_type  VARCHAR(50) NOT NULL,
    filter_json     TEXT NOT NULL,
    is_default      BOOLEAN DEFAULT FALSE,
    is_shared       BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_saved_filter_name UNIQUE (tenant_id, user_id, dashboard_type, name)
);

CREATE INDEX IF NOT EXISTS idx_saved_filter_lookup ON saved_filter(tenant_id, user_id, dashboard_type);

-- ==========================================
-- AI ASSISTANT MENU
-- ==========================================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('AI Assistant', '/ai-assistant', 'BrainCircuit', 'BUSINESS', 12)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin', 'Business User')
  AND m.menu_name = 'AI Assistant'
ON CONFLICT DO NOTHING;

-- ==========================================
-- AI CHAT HISTORY (optional - for saved conversations)
-- ==========================================
CREATE TABLE IF NOT EXISTS ai_chat_history (
    chat_id     BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL,
    user_id     BIGINT NOT NULL REFERENCES users(user_id),
    question    TEXT NOT NULL,
    generated_sql TEXT,
    summary     TEXT,
    row_count   INT,
    duration_ms BIGINT,
    is_error    BOOLEAN DEFAULT FALSE,
    error_msg   TEXT,
    created_at  TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_chat_tenant ON ai_chat_history(tenant_id, user_id);

-- ==========================================
-- DATA EXPLORER INDEXES (Merchant + Txn staging)
-- ==========================================
CREATE INDEX IF NOT EXISTS idx_stg_merch_tenant ON stg_merchant_master_raw (tenant_id);
CREATE INDEX IF NOT EXISTS idx_stg_merch_city ON stg_merchant_master_raw (tenant_id, city);
CREATE INDEX IF NOT EXISTS idx_stg_merch_mcc ON stg_merchant_master_raw (tenant_id, business_mcc);
CREATE INDEX IF NOT EXISTS idx_stg_merch_status ON stg_merchant_master_raw (tenant_id, merchant_status);
CREATE INDEX IF NOT EXISTS idx_stg_merch_partner ON stg_merchant_master_raw (tenant_id, referral_partner);
CREATE INDEX IF NOT EXISTS idx_stg_merch_industry ON stg_merchant_master_raw (tenant_id, industry_type);
CREATE INDEX IF NOT EXISTS idx_stg_merch_mid ON stg_merchant_master_raw (tenant_id, mid);
CREATE INDEX IF NOT EXISTS idx_stg_txn_scheme ON stg_trnx_raw (tenant_id, card_scheme);
CREATE INDEX IF NOT EXISTS idx_stg_txn_dest ON stg_trnx_raw (tenant_id, destination);
CREATE INDEX IF NOT EXISTS idx_stg_txn_type ON stg_trnx_raw (tenant_id, transaction_type);

-- ==========================================
-- DATA EXPLORER MENU
-- ==========================================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Data Explorer', '/explorer', 'Compass', 'BUSINESS', 11)
ON CONFLICT (path) DO NOTHING;

-- Map to Super Admin, Bank Admin, Business User
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin', 'Business User')
  AND m.menu_name = 'Data Explorer'
ON CONFLICT DO NOTHING;

-- ==========================================
-- SAVED FILTERS / VIEWS
-- ==========================================
CREATE TABLE IF NOT EXISTS saved_filter (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    user_id         BIGINT NOT NULL REFERENCES users(user_id),
    name            VARCHAR(100) NOT NULL,
    dashboard_type  VARCHAR(50) NOT NULL,
    filter_json     TEXT NOT NULL,
    is_default      BOOLEAN DEFAULT FALSE,
    is_shared       BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_saved_filter_name UNIQUE (tenant_id, user_id, dashboard_type, name)
);
CREATE INDEX IF NOT EXISTS idx_saved_filter_lookup ON saved_filter(tenant_id, user_id, dashboard_type);


