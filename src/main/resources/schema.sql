-- ==========================================
-- 0. CLEANUP (Dev Mode: Reset Schema)
-- ==========================================
-- Security / Core
DROP TABLE IF EXISTS user_tenant_access CASCADE;
DROP TABLE IF EXISTS sys_group_menu CASCADE;
DROP TABLE IF EXISTS sys_menu CASCADE;
DROP TABLE IF EXISTS sys_user_group CASCADE;
DROP TABLE IF EXISTS user_role CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS app_user CASCADE;
DROP TABLE IF EXISTS role CASCADE;
DROP TABLE IF EXISTS tenant CASCADE;
DROP TABLE IF EXISTS region CASCADE;
DROP TABLE IF EXISTS ref_country CASCADE;
DROP TABLE IF EXISTS audit_log CASCADE;

-- Business Domain
DROP TABLE IF EXISTS dim_terminal CASCADE;
DROP TABLE IF EXISTS dim_bank_account CASCADE;
DROP TABLE IF EXISTS dim_store CASCADE;
DROP TABLE IF EXISTS dim_merchant CASCADE;
DROP TABLE IF EXISTS dim_aggregator CASCADE;
DROP TABLE IF EXISTS bank_budget_target CASCADE;
DROP TABLE IF EXISTS merchant_lifecycle_status CASCADE;
DROP TABLE IF EXISTS merchant_opportunity_score CASCADE;
DROP TABLE IF EXISTS revenue_leakage_flags CASCADE;
DROP TABLE IF EXISTS merchant_contact CASCADE;
DROP TABLE IF EXISTS merchant_document CASCADE;
DROP TABLE IF EXISTS merchant_note CASCADE;
DROP TABLE IF EXISTS merchant_risk_profile CASCADE;
DROP TABLE IF EXISTS merchant_settlement_config CASCADE;
DROP TABLE IF EXISTS merchant_contract CASCADE;

-- Facts
DROP TABLE IF EXISTS fact_transaction CASCADE;
DROP TABLE IF EXISTS sum_daily_merchant CASCADE;
DROP TABLE IF EXISTS sum_monthly_bank CASCADE;
DROP TABLE IF EXISTS sum_daily_bank CASCADE;
DROP TABLE IF EXISTS sum_daily_mcc CASCADE;
DROP TABLE IF EXISTS merchant_activity CASCADE;
DROP TABLE IF EXISTS kpi_snapshot_daily CASCADE;
DROP TABLE IF EXISTS kpi_snapshot_monthly CASCADE;
DROP TABLE IF EXISTS batch_run_log CASCADE;
DROP TABLE IF EXISTS user_region_access CASCADE;

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
('Attrition Report', '/business/attrition', 'TrendingDown', 'BUSINESS', 8)
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
    latitude DECIMAL(9, 6),
    longitude DECIMAL(9, 6),
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
    transaction_id BIGSERIAL PRIMARY KEY,
    tenant_id INT NOT NULL REFERENCES tenant(tenant_id), -- RLS Key
    
    merchant_id BIGINT, 
    store_id BIGINT,
    terminal_id BIGINT,
    
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
    destination VARCHAR(50),
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
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
    
    PRIMARY KEY (summary_id, business_date)
) PARTITION BY RANGE (business_date);

-- Partitions
CREATE TABLE sum_daily_merchant_y2024 PARTITION OF sum_daily_merchant
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE sum_daily_merchant_y2025 PARTITION OF sum_daily_merchant
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE sum_daily_merchant_default PARTITION OF sum_daily_merchant DEFAULT;

-- Indexes
CREATE INDEX idx_sum_merch_tenant_date ON sum_daily_merchant (tenant_id, business_date);
CREATE INDEX idx_sum_merch_id ON sum_daily_merchant (merchant_id);
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
    
    PRIMARY KEY (summary_id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE sum_daily_scheme_y2024 PARTITION OF sum_daily_scheme FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE sum_daily_scheme_y2025 PARTITION OF sum_daily_scheme FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE sum_daily_scheme_default PARTITION OF sum_daily_scheme DEFAULT;

CREATE INDEX idx_sum_scheme_tenant_date ON sum_daily_scheme (tenant_id, business_date);
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
    
    PRIMARY KEY (summary_id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE sum_daily_channel_y2024 PARTITION OF sum_daily_channel FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE sum_daily_channel_y2025 PARTITION OF sum_daily_channel FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE sum_daily_channel_default PARTITION OF sum_daily_channel DEFAULT;

CREATE INDEX idx_sum_channel_tenant_date ON sum_daily_channel (tenant_id, business_date);
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
    
    PRIMARY KEY (summary_id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE sum_daily_terminal_y2024 PARTITION OF sum_daily_terminal FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE sum_daily_terminal_y2025 PARTITION OF sum_daily_terminal FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE sum_daily_terminal_default PARTITION OF sum_daily_terminal DEFAULT;

CREATE INDEX idx_sum_term_tenant_date ON sum_daily_terminal (tenant_id, business_date);
CREATE INDEX idx_sum_term_ids ON sum_daily_terminal (merchant_id, terminal_id);
ALTER TABLE sum_daily_terminal ENABLE ROW LEVEL SECURITY;


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

    PRIMARY KEY (summary_id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE sum_daily_bank_y2024 PARTITION OF sum_daily_bank FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE sum_daily_bank_y2025 PARTITION OF sum_daily_bank FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE sum_daily_bank_default PARTITION OF sum_daily_bank DEFAULT;

CREATE INDEX idx_sum_bank_tenant_date ON sum_daily_bank (tenant_id, business_date);
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

    PRIMARY KEY (summary_id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE sum_daily_finance_y2024 PARTITION OF sum_daily_finance FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE sum_daily_finance_y2025 PARTITION OF sum_daily_finance FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE sum_daily_finance_default PARTITION OF sum_daily_finance DEFAULT;

CREATE INDEX idx_sum_fin_tenant_date ON sum_daily_finance (tenant_id, business_date);
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
    
    PRIMARY KEY (summary_id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE sum_daily_insight_y2024 PARTITION OF sum_daily_insight FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE sum_daily_insight_y2025 PARTITION OF sum_daily_insight FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE sum_daily_insight_default PARTITION OF sum_daily_insight DEFAULT;

CREATE INDEX idx_sum_insight_tenant_date ON sum_daily_insight (tenant_id, business_date);
CREATE INDEX idx_sum_insight_merchant ON sum_daily_insight (merchant_id);
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
    tenant_id INT,
    job_name VARCHAR(100),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(20), -- COMPLETED, FAILED, RUNNING
    records_processed INT DEFAULT 0,
    records_failed INT DEFAULT 0,
    error_message TEXT
);

-- RLS Policies for New Tables
ALTER TABLE sum_daily_bank ENABLE ROW LEVEL SECURITY;
ALTER TABLE sum_daily_mcc ENABLE ROW LEVEL SECURITY;
ALTER TABLE merchant_activity ENABLE ROW LEVEL SECURITY;
ALTER TABLE kpi_snapshot_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE kpi_snapshot_monthly ENABLE ROW LEVEL SECURITY;
ALTER TABLE sum_daily_scheme ENABLE ROW LEVEL SECURITY;
ALTER TABLE sum_daily_channel ENABLE ROW LEVEL SECURITY;
ALTER TABLE sum_daily_terminal ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch_run_log ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_bank;
CREATE POLICY tenant_isolation_policy ON sum_daily_bank USING (tenant_id = get_current_tenant());
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_mcc;
CREATE POLICY tenant_isolation_policy ON sum_daily_mcc USING (tenant_id = get_current_tenant());
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_mcc;
CREATE POLICY tenant_isolation_policy ON sum_daily_mcc USING (tenant_id = get_current_tenant());
DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_activity_summary;
CREATE POLICY tenant_isolation_policy ON merchant_activity_summary USING (tenant_id = get_current_tenant());
DROP POLICY IF EXISTS tenant_isolation_policy ON kpi_snapshot_daily;
CREATE POLICY tenant_isolation_policy ON kpi_snapshot_daily USING (tenant_id = get_current_tenant());
DROP POLICY IF EXISTS tenant_isolation_policy ON kpi_snapshot_monthly;
CREATE POLICY tenant_isolation_policy ON kpi_snapshot_monthly USING (tenant_id = get_current_tenant());
DROP POLICY IF EXISTS tenant_isolation_policy ON batch_run_log;
CREATE POLICY tenant_isolation_policy ON batch_run_log USING (tenant_id = get_current_tenant());

DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_terminal;
CREATE POLICY tenant_isolation_policy ON sum_daily_terminal USING (tenant_id = get_current_tenant());


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


