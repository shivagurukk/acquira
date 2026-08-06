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

-- Integration Hub
DROP TABLE IF EXISTS integration_run_log CASCADE;
DROP TABLE IF EXISTS integration_schedule CASCADE;
DROP TABLE IF EXISTS integration_report CASCADE;
DROP TABLE IF EXISTS integration_connection CASCADE;

-- Report Builder & Email Campaigns
DROP TABLE IF EXISTS email_campaign_log CASCADE;
DROP TABLE IF EXISTS email_campaign CASCADE;
DROP TABLE IF EXISTS email_template_config CASCADE;
DROP TABLE IF EXISTS report_schedule CASCADE;
DROP TABLE IF EXISTS report_template CASCADE;

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
    phone_code VARCHAR(10),
    iso_numeric VARCHAR(3),              -- ISO 3166-1 numeric (e.g., 048, 840, 356)
    decimal_notation_value INTEGER DEFAULT 100 -- Divisor for raw amounts (e.g., 1000 for BHD, 100 for USD)
);

-- ==================================================================================
-- REF_CARD_SCHEME: Card scheme reference for TGEN509 card type resolution
-- ==================================================================================
CREATE TABLE IF NOT EXISTS ref_card_scheme (
    id INTEGER PRIMARY KEY,
    is_active BOOLEAN DEFAULT TRUE,
    code VARCHAR(10) UNIQUE NOT NULL,
    name VARCHAR(100),
    group_code VARCHAR(10),
    group_name VARCHAR(100),
    status INTEGER DEFAULT 1,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    card_type INTEGER,      -- 0=Generic, 1=Credit, 2=Debit, 3=Credit Prepaid, 4=Debit Prepaid
    card_subtype INTEGER    -- 0=Standard, 1=Standard, 2=Premium
);

-- Seed ref_card_scheme (17 rows matching AFSU.CardSchemes)
INSERT INTO ref_card_scheme (id, is_active, code, name, group_code, group_name, status, card_type, card_subtype) VALUES
(1,  true, 'VISA', 'Visa',                          'VISA', 'Visa',           1, 0, 0),
(2,  true, 'MCRD', 'MasterCard',                     'MCRD', 'MasterCard',     1, 0, 0),
(3,  true, 'AMEX', 'American Express',                'AMEX', 'Amex',           1, 0, 0),
(4,  true, 'VIDB', 'Visa Debit',                      'VISA', 'Visa',           1, 2, 0),
(5,  true, 'MCDB', 'MasterCard Debit',                'MCRD', 'MasterCard',     1, 2, 0),
(6,  true, 'MCCR', 'MasterCard Credit',               'MCRD', 'MasterCard',     1, 1, 0),
(7,  true, 'VICR', 'Visa Credit',                     'VISA', 'Visa',           1, 1, 0),
(8,  true, 'UPI',  'UnionPay International',           'UPI',  'UnionPay',       1, 0, 0),
(9,  true, 'JCB',  'JCB',                              'JCB',  'JCB',            1, 0, 0),
(10, true, 'MCPM', 'MasterCard Premium Credit',        'MCRD', 'MasterCard',     1, 1, 2),
(11, true, 'MCSD', 'MasterCard Standard Credit',       'MCRD', 'MasterCard',     1, 1, 1),
(12, true, 'VICP', 'Visa Credit Premium',              'VISA', 'Visa',           1, 1, 2),
(13, true, 'VIPM', 'Visa Premium',                     'VISA', 'Visa',           1, 1, 2),
(14, true, 'VISD', 'Visa Standard',                    'VISA', 'Visa',           1, 1, 1),
(15, true, 'MCCP', 'MasterCard Credit Prepaid',        'MCRD', 'MasterCard',     1, 3, 0),
(16, true, 'MCDP', 'MasterCard Debit Prepaid',         'MCRD', 'MasterCard',     1, 4, 0),
(17, true, 'ZPET', 'Debit (Zakat/PET)',                'ZPET', 'Debit Zakat',    1, 2, 0)
ON CONFLICT (code) DO NOTHING;

-- Seed ref_country rows for common currencies used in TGEN509 processing

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

-- ==========================================
-- 2. COMPLETE MENU REGISTRY
-- Every menu here maps 1:1 to a route in App.jsx
-- No hardcoded menus in frontend — this is the single source of truth
-- ==========================================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
-- EXECUTIVE
('Dashboard',                '/dashboard',                        'LayoutDashboard', 'EXECUTIVE',      1),
('Executive Dashboard',      '/business/executive-dashboard-v2',  'Presentation',    'EXECUTIVE',      2),

-- MERCHANT MGT
('Merchant Universe',        '/merchants',                        'Store',           'MERCHANT MGT',   1),
('Transactions',             '/transactions',                     'List',            'MERCHANT MGT',   2),
('Merchant Summary',         '/merchant-summary',                 'Table',           'MERCHANT MGT',   3),
('Merchant Insight Hub',     '/merchant/insight-hub',             'PieChart',        'MERCHANT MGT',   4),
('Transaction Trends',       '/trends/hub',                       'Activity',        'MERCHANT MGT',   5),

-- BUSINESS
('Business Dashboard',       '/business/dashboard',               'LayoutGrid',      'BUSINESS',       0),
('Volume & Revenue',         '/business/volume-revenue',          'BarChart3',       'BUSINESS',       1),
('Merchant Financial',       '/business/merchant-financial',      'DollarSign',      'BUSINESS',       2),
('Performance Trends',       '/business/performance',             'TrendingUp',      'BUSINESS',       3),
('Debit & Prepaid Metrics',  '/business/debit-prepaid',           'CreditCard',      'BUSINESS',       4),
('Attrition Report',         '/business/attrition',               'TrendingDown',    'BUSINESS',       5),
('Zero Transaction Report',  '/business/zero-transaction',        'AlertTriangle',   'BUSINESS',       6),
('Merchant Growth Heatmap',  '/business/heatmap',                 'Grid',            'BUSINESS',       7),
('Daily Merchant Dashboard', '/business/daily-dashboard',         'Calendar',        'BUSINESS',       8),
('Merchant Analytics',       '/business/merchant-analytics',      'BarChart2',       'BUSINESS',       9),
('Merchant Comparison',      '/business/comparison',              'Scale',           'BUSINESS',      10),
('Report Manager',           '/business/report-manager',          'FileText',        'BUSINESS',      11),
('Opportunity Intelligence', '/business/opportunity',             'Target',          'BUSINESS',      12),
('Group Reports',            '/business/groups',                  'FolderKanban',    'BUSINESS',      13),
('Data Explorer',            '/explorer',                         'Compass',         'BUSINESS',      14),
('AI Assistant',             '/ai-assistant',                     'BrainCircuit',    'BUSINESS',      15),

-- SALES
('Sales Team Management',    '/sales/team-management',            'Users',           'SALES',          1),

-- FINANCE
('Finance Dashboard',        '/finance/dashboard',                'PieChart',        'FINANCE',        1),
('Finance Summary',          '/finance/summary',                  'BookOpen',        'FINANCE',        2),
('Finance Lists',            '/finance/lists',                    'ClipboardList',   'FINANCE',        3),

-- OPERATIONS
('Upload Files',             '/upload',                           'Upload',          'OPERATIONS',     1),
('Server File Processor',    '/ops/server-file',                  'HardDrive',       'OPERATIONS',     2),
('Batch Logs',               '/ops/batch-logs',                   'Activity',        'OPERATIONS',     3),
('Email Manager',            '/business/emails',                  'Mail',            'OPERATIONS',     4),

-- ADMINISTRATION
('User Management',          '/users',                            'Users',           'ADMINISTRATION', 1),
('Bank Setup',               '/tenants',                          'Building',        'ADMINISTRATION', 2),
('Group Management',         '/admin/groups',                     'Shield',          'ADMINISTRATION', 3),
('SMTP Settings',            '/admin/smtp-settings',              'Settings',        'ADMINISTRATION', 4),
('Audit Logs',               '/admin/audit-logs',                 'ScrollText',      'ADMINISTRATION', 5),
('Backup & Restore',         '/admin/backups',                    'Database',        'ADMINISTRATION', 6)
ON CONFLICT (path) DO NOTHING;

-- ==========================================
-- 3. GROUP → MENU ASSIGNMENTS (RBAC)
-- ==========================================

-- Super Admin: ALL menus
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
ON CONFLICT DO NOTHING;

-- Bank Admin: Everything except sensitive admin pages
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Bank Admin'
  AND m.path NOT IN ('/admin/groups', '/admin/smtp-settings', '/admin/audit-logs', '/admin/backups')
ON CONFLICT DO NOTHING;

-- Business User: Executive + Business + Merchant Mgt + Sales
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Business User'
  AND m.category IN ('EXECUTIVE', 'BUSINESS', 'MERCHANT MGT', 'SALES')
ON CONFLICT DO NOTHING;

-- Finance User: Dashboard + Finance + Merchant Mgt
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Finance User'
  AND (m.category IN ('FINANCE', 'MERCHANT MGT') OR m.path = '/dashboard')
ON CONFLICT DO NOTHING;

-- Ops User: Dashboard + Operations + Backup
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Ops User'
  AND (m.category = 'OPERATIONS' OR m.path IN ('/dashboard', '/admin/backups'))
ON CONFLICT DO NOTHING;

-- 1.2 Tenant (The Core Institution/Bank Unit)
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
    last_login TIMESTAMP,
    -- Password Management
    must_change_password BOOLEAN DEFAULT FALSE,
    password_changed_at TIMESTAMP,
    -- Account Lockout
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMP,
    last_failed_login TIMESTAMP
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
    username VARCHAR(50),
    action_type VARCHAR(50), -- LOGIN, EXPORT, BATCH_RUN
    details TEXT,
    ip_address VARCHAR(45),
    event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    http_method VARCHAR(10),
    endpoint VARCHAR(255),
    status_code INT,
    user_agent VARCHAR(500),
    category VARCHAR(50),
    entity_type VARCHAR(50),
    entity_id VARCHAR(100),
    duration_ms BIGINT
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
-- #21: Use MONTHLY partitions for fact_transaction (aligned with PartitionMaintenanceService)
-- Yearly partitions removed to avoid overlap with monthly partitions.
-- PartitionMaintenanceService.ensurePartitionsForYear() creates monthly partitions
-- (e.g. fact_transaction_y2025m01 ... fact_transaction_y2025m12) at application startup.
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
    total_msf DECIMAL(21, 4),
    total_interchange DECIMAL(19, 2),
    total_scheme_fee DECIMAL(19, 2), 
    total_margin DECIMAL(19, 2),
    
    total_debit_prepaid_volume DECIMAL(19, 2) DEFAULT 0,
    total_credit_volume DECIMAL(19, 2) DEFAULT 0,
    sales_user_id VARCHAR(50),
    
    unique_customer_count BIGINT DEFAULT 0,
    top_spending_customer_id VARCHAR(50),
    top_spending_amount DECIMAL(19, 2),
    
    -- Base Currency Volume (Store Base Currency Amount for merchant-facing PDF)
    total_base_volume DECIMAL(19, 2) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4),
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    dom_debit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_debit_optin DECIMAL(19, 2) DEFAULT 0, -- Opt-in Volume (DCC)

    -- Domestic Credit
    dom_credit_cnt BIGINT DEFAULT 0,
    dom_credit_vol DECIMAL(19, 2) DEFAULT 0,
    dom_credit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_credit_optin DECIMAL(19, 2) DEFAULT 0,

    -- International
    int_cnt BIGINT DEFAULT 0,
    int_vol DECIMAL(19, 2) DEFAULT 0,
    int_msf DECIMAL(21, 4) DEFAULT 0,
    int_optin DECIMAL(19, 2) DEFAULT 0,

    -- Totals
    total_vol DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(21, 4) DEFAULT 0,

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
    total_msf DECIMAL(21, 4) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    volatility_index DECIMAL(19, 4),
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
VALUES ('BANK001', 'Acquira Bank', 'ACQ', 'United Arab Emirates', 'AED')
ON CONFLICT (institution_id) DO NOTHING;

-- 4.2 Default Users
-- Password is 'password' (BCrypt encoded: $2a$10$slYQmyNdGzTn7ZLBXBChFOC9f6kFjAqPhccnP6DxlTzceYkEGCcqi)
INSERT INTO users (username, password_hash, email, role, is_active, must_change_password) VALUES 
('admin', '{noop}password', 'admin@acquira.com', 'ROLE_SUPER_ADMIN', true, false)
ON CONFLICT (username) DO UPDATE 
SET password_hash = EXCLUDED.password_hash, must_change_password = FALSE, role = 'ROLE_SUPER_ADMIN';

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
-- (V2026_08_01_01) idx_fact_txn_card removed: no report query reads fact by card_number (card lookups use sum_monthly_card); at 300M+ rows/yr it was the most expensive index to maintain. Recreating it here would undo that migration's DROP.

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
-- SALES TEAM MAPPING & ASSIGNMENT
-- ==========================================
CREATE TABLE IF NOT EXISTS sales_team_mapping (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    team_lead_name  VARCHAR(100) NOT NULL,
    team_lead_email VARCHAR(150) NOT NULL,
    is_default      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_team_tenant_email UNIQUE (tenant_id, team_lead_email)
);
CREATE INDEX IF NOT EXISTS idx_sales_team_tenant ON sales_team_mapping(tenant_id);

CREATE TABLE IF NOT EXISTS sales_user_assignment (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    sales_user_id   VARCHAR(100) NOT NULL,
    team_lead_id    BIGINT NOT NULL REFERENCES sales_team_mapping(id),
    assigned_at     TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_user_tenant UNIQUE (tenant_id, sales_user_id)
);
CREATE INDEX IF NOT EXISTS idx_sales_assign_tenant ON sales_user_assignment(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_assign_lead ON sales_user_assignment(team_lead_id);

-- Default Team Lead (auto-assign unmapped sales users to this lead)
INSERT INTO sales_team_mapping (tenant_id, team_lead_name, team_lead_email, is_default)
VALUES (1, 'Default Team Lead', 'default-lead@acquira.com', true)
ON CONFLICT (tenant_id, team_lead_email) DO UPDATE SET is_default = true;

-- ==========================================
-- PASSWORD HISTORY
-- ==========================================
CREATE TABLE IF NOT EXISTS password_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_password_history_user ON password_history(user_id);

-- ==========================================
-- PASSWORD RESET TOKENS
-- ==========================================
CREATE TABLE IF NOT EXISTS password_reset_token (
    token_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_reset_token ON password_reset_token(token);

-- ==========================================
-- ALTER existing users table (for upgrades)
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts INT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_failed_login TIMESTAMP;

-- ==========================================
-- SSO & ACCESS REQUEST SUPPORT
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_provider VARCHAR(20);       -- 'MICROSOFT', NULL
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_id VARCHAR(255);            -- Azure AD Object ID
ALTER TABLE users ADD COLUMN IF NOT EXISTS approval_status VARCHAR(20) DEFAULT 'APPROVED';
    -- APPROVED (normal users), PENDING (SSO requests), REJECTED
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR(150);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS role_in_tenant VARCHAR(50);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS is_default_tenant BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS access_request (
    request_id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(150),
    sso_provider VARCHAR(20),
    sso_id VARCHAR(255),
    requested_tenant_id INT REFERENCES tenant(tenant_id),
    message TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    reviewed_by BIGINT REFERENCES users(user_id),
    reviewed_at TIMESTAMP,
    review_notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- SSO Configuration per tenant (admin toggle)
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_enabled', 'false', 'BOOLEAN'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_provider', 'MICROSOFT', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_tenant_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_secret', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Ensure existing users are NOT forced to change password
UPDATE users SET must_change_password = FALSE WHERE must_change_password IS NULL;

-- ==========================================
-- PASSWORD & LOCKOUT CONFIG (per-tenant)
-- ==========================================
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_history_count', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_min_length', '8', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'max_failed_logins', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'lockout_duration_minutes', '15', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_reset_token_expiry_hours', '1', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Seed admin password into history
INSERT INTO password_history (user_id, password_hash)
SELECT user_id, password_hash FROM users WHERE username = 'admin'
ON CONFLICT DO NOTHING;
-- AI Assistant menu: managed in consolidated menu block above

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
-- Join key for the stagingToFact store_id/terminal_id fix-up UPDATEs, which match
-- fact rows to staging on (payment_date, arn). Without this the fix-ups hash the
-- whole staging table on every upload. See V2026_08_03_01.
CREATE INDEX IF NOT EXISTS idx_stg_txn_arn_date ON stg_trnx_raw (tenant_id, arn, payment_date);

-- Data Explorer menu: managed in consolidated menu block above
-- (saved_filter already created above)

-- ==========================================
-- DATA INTEGRATION HUB
-- ==========================================

-- External DB connections (per tenant)
CREATE TABLE IF NOT EXISTS integration_connection (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    db_type VARCHAR(50) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    db_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_password TEXT NOT NULL,
    timeout_seconds INTEGER DEFAULT 30,
    max_retries INTEGER DEFAULT 3,
    is_active BOOLEAN DEFAULT TRUE,
    last_test_at TIMESTAMP,
    last_test_status VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_conn_tenant ON integration_connection(tenant_id);

-- Report configs (per tenant, per type: MERCHANT or TRANSACTION)
CREATE TABLE IF NOT EXISTS integration_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connection_id BIGINT REFERENCES integration_connection(id),
    name VARCHAR(255) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    sql_text TEXT NOT NULL,
    column_mapping TEXT,
    description TEXT,
    param_schema TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    approved_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_report_tenant ON integration_report(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_report_type ON integration_report(tenant_id, report_type);

-- Schedule configs (independent per report per tenant)
CREATE TABLE IF NOT EXISTS integration_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_sched_tenant ON integration_schedule(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_sched_enabled ON integration_schedule(is_enabled);

-- Run history with retry tracking
CREATE TABLE IF NOT EXISTS integration_run_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    schedule_id BIGINT REFERENCES integration_schedule(id),
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_number INTEGER DEFAULT 1,
    max_retries INTEGER DEFAULT 3,
    rows_fetched INTEGER DEFAULT 0,
    rows_processed INTEGER DEFAULT 0,
    rows_failed INTEGER DEFAULT 0,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    error_message TEXT,
    date_range_from DATE,
    date_range_to DATE,
    duration_ms BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_run_tenant ON integration_run_log(tenant_id, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_intg_run_status ON integration_run_log(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_intg_run_report ON integration_run_log(report_id);

-- Menu entries for Data Integration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Integration Hub',       '/admin/integration',              'Cable',           'DATA INTEGRATION', 1),
('DB Connections',        '/admin/integration/connections',   'Database',        'DATA INTEGRATION', 2),
('Report Configs',        '/admin/integration/reports',       'FileCode',        'DATA INTEGRATION', 3),
('Schedules',             '/admin/integration/schedules',     'Clock',           'DATA INTEGRATION', 4),
('Run History',           '/admin/integration/runs',          'ScrollText',      'DATA INTEGRATION', 5)
ON CONFLICT (path) DO NOTHING;

-- Grant integration menus to SUPER_ADMIN and ADMIN groups
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'SUPER_ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

-- ==========================================
-- REPORT BUILDER & TEMPLATES
-- ==========================================

CREATE TABLE IF NOT EXISTS report_template (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    config_json TEXT NOT NULL,
    is_shared BOOLEAN DEFAULT FALSE,
    last_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_tpl_tenant ON report_template(tenant_id, user_id);

CREATE TABLE IF NOT EXISTS report_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    template_id BIGINT REFERENCES report_template(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    delivery_method VARCHAR(20) DEFAULT 'EMAIL',
    recipient_emails TEXT,
    export_format VARCHAR(10) DEFAULT 'EXCEL',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_sched_tenant ON report_schedule(tenant_id);

-- ==========================================
-- EMAIL CAMPAIGN SYSTEM
-- ==========================================

CREATE TABLE IF NOT EXISTS email_template_config (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_type VARCHAR(50) NOT NULL,
    subject_template VARCHAR(500) NOT NULL,
    body_html TEXT NOT NULL,
    body_text TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    is_default_for_type BOOLEAN DEFAULT FALSE,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_tpl_tenant ON email_template_config(tenant_id);

CREATE TABLE IF NOT EXISTS email_campaign (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_id BIGINT REFERENCES email_template_config(id),
    campaign_type VARCHAR(20) NOT NULL,
    recipient_filter_json TEXT,
    attachment_type VARCHAR(20) DEFAULT 'NONE',
    attachment_report_template_id BIGINT,
    statement_month VARCHAR(10),
    schedule_cron VARCHAR(100),
    schedule_timezone VARCHAR(50),
    status VARCHAR(20) DEFAULT 'DRAFT',
    total_recipients INTEGER DEFAULT 0,
    sent_count INTEGER DEFAULT 0,
    failed_count INTEGER DEFAULT 0,
    sent_at TIMESTAMP,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_camp_tenant ON email_campaign(tenant_id);
CREATE INDEX IF NOT EXISTS idx_email_camp_status ON email_campaign(tenant_id, status);

CREATE TABLE IF NOT EXISTS email_campaign_log (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES email_campaign(id),
    tenant_id BIGINT NOT NULL,
    merchant_id BIGINT,
    merchant_name VARCHAR(255),
    recipient_email VARCHAR(255),
    subject_rendered VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    sent_at TIMESTAMP,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_clog_campaign ON email_campaign_log(campaign_id);
CREATE INDEX IF NOT EXISTS idx_email_clog_tenant ON email_campaign_log(tenant_id, sent_at DESC);

-- Default email templates
INSERT INTO email_template_config (tenant_id, name, template_type, subject_template, body_html, is_active, is_default_for_type) VALUES
(1, 'Monthly Statement', 'STATEMENT',
 'Your {{month}} Performance Statement - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:#0f172a;color:#fff;padding:30px;text-align:center}.header h1{margin:0;font-size:24px}.content{padding:40px 30px}.greeting{font-size:18px;color:#1e293b;margin-bottom:20px}.card{background:#f1f5f9;border-radius:8px;padding:20px;margin-bottom:20px;border:1px solid #e2e8f0}.stats{display:flex;gap:20px;margin:20px 0}.stat{flex:1;text-align:center;padding:15px;background:#fff;border-radius:8px;border:1px solid #e2e8f0}.stat-value{font-size:22px;font-weight:700;color:#0f172a}.stat-label{font-size:12px;color:#64748b;text-transform:uppercase}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8;border-top:1px solid #e2e8f0}</style></head><body><div class="container"><div class="header"><h1>{{tenant_name}}</h1></div><div class="content"><div class="greeting">Dear {{contact_name}},</div><p>Your performance statement for <strong>{{month}}</strong> is now available.</p><div class="card"><div style="font-size:14px;color:#64748b;text-transform:uppercase;font-weight:600;margin-bottom:10px">Performance Summary</div><div class="stats"><div class="stat"><div class="stat-value">{{total_count}}</div><div class="stat-label">Transactions</div></div><div class="stat"><div class="stat-value">{{total_volume}}</div><div class="stat-label">Volume</div></div><div class="stat"><div class="stat-value">{{total_msf}}</div><div class="stat-label">MSF Revenue</div></div></div></div><p style="color:#64748b">Please find the detailed PDF report attached to this email.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.<br>This is an automated message.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Welcome Email', 'WELCOME',
 'Welcome to {{tenant_name}} - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:linear-gradient(135deg,#2563eb,#7c3aed);color:#fff;padding:40px;text-align:center}.header h1{margin:0;font-size:28px}.content{padding:40px 30px}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>Welcome!</h1></div><div class="content"><h2>Hello {{contact_name}},</h2><p>Welcome to <strong>{{tenant_name}}</strong>! We are excited to have <strong>{{merchant_name}}</strong> onboard.</p><p>Your Merchant ID is <strong>{{mid}}</strong>.</p><p>You can view your transaction data, performance analytics, and monthly statements through our portal.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Dormancy Alert', 'ALERT',
 'Action Required: {{merchant_name}} - No recent transactions',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden}.header{background:#dc2626;color:#fff;padding:30px;text-align:center}.content{padding:40px 30px}.alert-box{background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:20px;margin:20px 0}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>⚠ Activity Alert</h1></div><div class="content"><p>Dear Team,</p><div class="alert-box"><strong>{{merchant_name}}</strong> (MID: {{mid}}) has not processed transactions in <strong>{{days_since_last_txn}} days</strong>.</div><p>Location: {{city}}<br>Status: {{merchant_status}}<br>Stores: {{store_count}} | Terminals: {{terminal_count}}</p><p>Please follow up to ensure the merchant is still active.</p></div><div class="footer">&copy; 2026 {{tenant_name}}</div></div></body></html>',
 TRUE, TRUE)
ON CONFLICT DO NOTHING;

-- Menu entries for Email Campaign Hub
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Email Campaigns',  '/admin/email-campaigns',  'MailOpen',  'OPERATIONS', 4)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN') AND m.path = '/admin/email-campaigns'
ON CONFLICT DO NOTHING;

-- Menu entry for SSO Settings
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('SSO Settings',  '/admin/sso-settings',  'Shield',  'ADMINISTRATION', 5)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN') AND m.path = '/admin/sso-settings'
ON CONFLICT DO NOTHING;

-- Menu entry for Data Migration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Data Migration',  '/admin/data-migration',  'DatabaseZap',  'ADMINISTRATION', 7)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin') AND m.path = '/admin/data-migration'
ON CONFLICT DO NOTHING;

-- Menu entry for Sales Leaderboard
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Sales Leaderboard',  '/sales/leaderboard',  'Trophy',  'SALES', 2)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN', 'BUSINESS') AND m.path = '/sales/leaderboard'
ON CONFLICT DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- PDF Optimization — contact_email + email_queue
-- ═══════════════════════════════════════════════════════════

-- Add contact_email to dim_merchant for merchant report emailing
-- (Using ADD COLUMN IF NOT EXISTS — avoids DO $ blocks which break Spring ScriptUtils)
ALTER TABLE dim_merchant ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255);

-- Email queue table for async email processing
CREATE TABLE IF NOT EXISTS email_queue (
    id              BIGSERIAL PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(500),
    body            TEXT,
    attachment_path VARCHAR(1000),
    status          VARCHAR(20) DEFAULT 'PENDING',
    error_message   TEXT,
    retry_count     INT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    sent_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_email_queue_status ON email_queue(status);
CREATE INDEX IF NOT EXISTS idx_email_queue_pending ON email_queue(status, created_at) WHERE status = 'PENDING';

-- Refresh Token tracking (#14: rotation + revocation)
CREATE TABLE IF NOT EXISTS refresh_token (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    issued_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by     VARCHAR(128),
    user_agent      VARCHAR(500),
    ip_address      VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_username ON refresh_token(username);
CREATE INDEX IF NOT EXISTS idx_refresh_token_hash ON refresh_token(token_hash);

-- SSO State Tokens (#7: persist across restart)
CREATE TABLE IF NOT EXISTS sso_state_token (
    state_token     VARCHAR(100) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_sso_state_expires ON sso_state_token(expires_at);

-- ═══════════════════════════════════════════════════════════
-- Menu entries for Session 17 new admin screens
-- ═══════════════════════════════════════════════════════════

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Security Settings',  '/admin/security-settings',  'ShieldCheck',  'ADMINISTRATION', 6),
('Alerts & Notifications',  '/admin/alerts',  'BellRing',  'OPERATIONS', 5),
('API Management',  '/admin/api-management',  'Code',  'ADMINISTRATION', 8)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'SUPER_ADMIN', 'ADMIN')
  AND m.path IN ('/admin/security-settings', '/admin/alerts', '/admin/api-management')
ON CONFLICT DO NOTHING;


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

-- ==========================================
-- 2. COMPLETE MENU REGISTRY
-- Every menu here maps 1:1 to a route in App.jsx
-- No hardcoded menus in frontend — this is the single source of truth
-- ==========================================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
-- EXECUTIVE
('Dashboard',                '/dashboard',                        'LayoutDashboard', 'EXECUTIVE',      1),
('Executive Dashboard',      '/business/executive-dashboard-v2',  'Presentation',    'EXECUTIVE',      2),

-- MERCHANT MGT
('Merchant Universe',        '/merchants',                        'Store',           'MERCHANT MGT',   1),
('Transactions',             '/transactions',                     'List',            'MERCHANT MGT',   2),
('Merchant Summary',         '/merchant-summary',                 'Table',           'MERCHANT MGT',   3),
('Merchant Insight Hub',     '/merchant/insight-hub',             'PieChart',        'MERCHANT MGT',   4),
('Transaction Trends',       '/trends/hub',                       'Activity',        'MERCHANT MGT',   5),

-- BUSINESS
('Business Dashboard',       '/business/dashboard',               'LayoutGrid',      'BUSINESS',       0),
('Volume & Revenue',         '/business/volume-revenue',          'BarChart3',       'BUSINESS',       1),
('Merchant Financial',       '/business/merchant-financial',      'DollarSign',      'BUSINESS',       2),
('Performance Trends',       '/business/performance',             'TrendingUp',      'BUSINESS',       3),
('Debit & Prepaid Metrics',  '/business/debit-prepaid',           'CreditCard',      'BUSINESS',       4),
('Attrition Report',         '/business/attrition',               'TrendingDown',    'BUSINESS',       5),
('Zero Transaction Report',  '/business/zero-transaction',        'AlertTriangle',   'BUSINESS',       6),
('Merchant Growth Heatmap',  '/business/heatmap',                 'Grid',            'BUSINESS',       7),
('Daily Merchant Dashboard', '/business/daily-dashboard',         'Calendar',        'BUSINESS',       8),
('Merchant Analytics',       '/business/merchant-analytics',      'BarChart2',       'BUSINESS',       9),
('Merchant Comparison',      '/business/comparison',              'Scale',           'BUSINESS',      10),
('Report Manager',           '/business/report-manager',          'FileText',        'BUSINESS',      11),
('Opportunity Intelligence', '/business/opportunity',             'Target',          'BUSINESS',      12),
('Group Reports',            '/business/groups',                  'FolderKanban',    'BUSINESS',      13),
('Data Explorer',            '/explorer',                         'Compass',         'BUSINESS',      14),
('AI Assistant',             '/ai-assistant',                     'BrainCircuit',    'BUSINESS',      15),

-- SALES
('Sales Team Management',    '/sales/team-management',            'Users',           'SALES',          1),

-- FINANCE
('Finance Dashboard',        '/finance/dashboard',                'PieChart',        'FINANCE',        1),
('Finance Summary',          '/finance/summary',                  'BookOpen',        'FINANCE',        2),
('Finance Lists',            '/finance/lists',                    'ClipboardList',   'FINANCE',        3),

-- OPERATIONS
('Upload Files',             '/upload',                           'Upload',          'OPERATIONS',     1),
('Server File Processor',    '/ops/server-file',                  'HardDrive',       'OPERATIONS',     2),
('Batch Logs',               '/ops/batch-logs',                   'Activity',        'OPERATIONS',     3),
('Email Manager',            '/business/emails',                  'Mail',            'OPERATIONS',     4),

-- ADMINISTRATION
('User Management',          '/users',                            'Users',           'ADMINISTRATION', 1),
('Bank Setup',               '/tenants',                          'Building',        'ADMINISTRATION', 2),
('Group Management',         '/admin/groups',                     'Shield',          'ADMINISTRATION', 3),
('SMTP Settings',            '/admin/smtp-settings',              'Settings',        'ADMINISTRATION', 4),
('Audit Logs',               '/admin/audit-logs',                 'ScrollText',      'ADMINISTRATION', 5),
('Backup & Restore',         '/admin/backups',                    'Database',        'ADMINISTRATION', 6)
ON CONFLICT (path) DO NOTHING;

-- ==========================================
-- 3. GROUP → MENU ASSIGNMENTS (RBAC)
-- ==========================================

-- Super Admin: ALL menus
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
ON CONFLICT DO NOTHING;

-- Bank Admin: Everything except sensitive admin pages
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Bank Admin'
  AND m.path NOT IN ('/admin/groups', '/admin/smtp-settings', '/admin/audit-logs', '/admin/backups')
ON CONFLICT DO NOTHING;

-- Business User: Executive + Business + Merchant Mgt + Sales
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Business User'
  AND m.category IN ('EXECUTIVE', 'BUSINESS', 'MERCHANT MGT', 'SALES')
ON CONFLICT DO NOTHING;

-- Finance User: Dashboard + Finance + Merchant Mgt
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Finance User'
  AND (m.category IN ('FINANCE', 'MERCHANT MGT') OR m.path = '/dashboard')
ON CONFLICT DO NOTHING;

-- Ops User: Dashboard + Operations + Backup
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Ops User'
  AND (m.category = 'OPERATIONS' OR m.path IN ('/dashboard', '/admin/backups'))
ON CONFLICT DO NOTHING;

-- 1.2 Tenant (The Core Institution/Bank Unit)
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
    last_login TIMESTAMP,
    -- Password Management
    must_change_password BOOLEAN DEFAULT FALSE,
    password_changed_at TIMESTAMP,
    -- Account Lockout
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMP,
    last_failed_login TIMESTAMP
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
    username VARCHAR(50),
    action_type VARCHAR(50), -- LOGIN, EXPORT, BATCH_RUN
    details TEXT,
    ip_address VARCHAR(45),
    event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    http_method VARCHAR(10),
    endpoint VARCHAR(255),
    status_code INT,
    user_agent VARCHAR(500),
    category VARCHAR(50),
    entity_type VARCHAR(50),
    entity_id VARCHAR(100),
    duration_ms BIGINT
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
-- #21: Use MONTHLY partitions for fact_transaction (aligned with PartitionMaintenanceService)
-- Yearly partitions removed to avoid overlap with monthly partitions.
-- PartitionMaintenanceService.ensurePartitionsForYear() creates monthly partitions
-- (e.g. fact_transaction_y2025m01 ... fact_transaction_y2025m12) at application startup.
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
    total_msf DECIMAL(21, 4),
    total_interchange DECIMAL(19, 2),
    total_scheme_fee DECIMAL(19, 2), 
    total_margin DECIMAL(19, 2),
    
    total_debit_prepaid_volume DECIMAL(19, 2) DEFAULT 0,
    total_credit_volume DECIMAL(19, 2) DEFAULT 0,
    sales_user_id VARCHAR(50),
    
    unique_customer_count BIGINT DEFAULT 0,
    top_spending_customer_id VARCHAR(50),
    top_spending_amount DECIMAL(19, 2),
    
    -- Base Currency Volume (Store Base Currency Amount for merchant-facing PDF)
    total_base_volume DECIMAL(19, 2) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4),
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    dom_debit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_debit_optin DECIMAL(19, 2) DEFAULT 0, -- Opt-in Volume (DCC)

    -- Domestic Credit
    dom_credit_cnt BIGINT DEFAULT 0,
    dom_credit_vol DECIMAL(19, 2) DEFAULT 0,
    dom_credit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_credit_optin DECIMAL(19, 2) DEFAULT 0,

    -- International
    int_cnt BIGINT DEFAULT 0,
    int_vol DECIMAL(19, 2) DEFAULT 0,
    int_msf DECIMAL(21, 4) DEFAULT 0,
    int_optin DECIMAL(19, 2) DEFAULT 0,

    -- Totals
    total_vol DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(21, 4) DEFAULT 0,

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
    total_msf DECIMAL(21, 4) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    volatility_index DECIMAL(19, 4),
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
INSERT INTO users (username, password_hash, email, role, is_active, must_change_password) VALUES 
('admin', '{noop}password', 'admin@acquira.com', 'ROLE_SUPER_ADMIN', true, false)
ON CONFLICT (username) DO UPDATE 
SET password_hash = EXCLUDED.password_hash, must_change_password = FALSE, role = 'ROLE_SUPER_ADMIN';

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
-- (V2026_08_01_01) idx_fact_txn_card removed: no report query reads fact by card_number (card lookups use sum_monthly_card); at 300M+ rows/yr it was the most expensive index to maintain. Recreating it here would undo that migration's DROP.

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
-- SALES TEAM MAPPING & ASSIGNMENT
-- ==========================================
CREATE TABLE IF NOT EXISTS sales_team_mapping (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    team_lead_name  VARCHAR(100) NOT NULL,
    team_lead_email VARCHAR(150) NOT NULL,
    is_default      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_team_tenant_email UNIQUE (tenant_id, team_lead_email)
);
CREATE INDEX IF NOT EXISTS idx_sales_team_tenant ON sales_team_mapping(tenant_id);

CREATE TABLE IF NOT EXISTS sales_user_assignment (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    sales_user_id   VARCHAR(100) NOT NULL,
    team_lead_id    BIGINT NOT NULL REFERENCES sales_team_mapping(id),
    assigned_at     TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_user_tenant UNIQUE (tenant_id, sales_user_id)
);
CREATE INDEX IF NOT EXISTS idx_sales_assign_tenant ON sales_user_assignment(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_assign_lead ON sales_user_assignment(team_lead_id);

-- Default Team Lead (auto-assign unmapped sales users to this lead)
INSERT INTO sales_team_mapping (tenant_id, team_lead_name, team_lead_email, is_default)
VALUES (1, 'Default Team Lead', 'default-lead@acquira.com', true)
ON CONFLICT (tenant_id, team_lead_email) DO UPDATE SET is_default = true;

-- ==========================================
-- PASSWORD HISTORY
-- ==========================================
CREATE TABLE IF NOT EXISTS password_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_password_history_user ON password_history(user_id);

-- ==========================================
-- PASSWORD RESET TOKENS
-- ==========================================
CREATE TABLE IF NOT EXISTS password_reset_token (
    token_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_reset_token ON password_reset_token(token);

-- ==========================================
-- ALTER existing users table (for upgrades)
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts INT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_failed_login TIMESTAMP;

-- ==========================================
-- SSO & ACCESS REQUEST SUPPORT
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_provider VARCHAR(20);       -- 'MICROSOFT', NULL
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_id VARCHAR(255);            -- Azure AD Object ID
ALTER TABLE users ADD COLUMN IF NOT EXISTS approval_status VARCHAR(20) DEFAULT 'APPROVED';
    -- APPROVED (normal users), PENDING (SSO requests), REJECTED
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR(150);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS role_in_tenant VARCHAR(50);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS is_default_tenant BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS access_request (
    request_id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(150),
    sso_provider VARCHAR(20),
    sso_id VARCHAR(255),
    requested_tenant_id INT REFERENCES tenant(tenant_id),
    message TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    reviewed_by BIGINT REFERENCES users(user_id),
    reviewed_at TIMESTAMP,
    review_notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- SSO Configuration per tenant (admin toggle)
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_enabled', 'false', 'BOOLEAN'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_provider', 'MICROSOFT', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_tenant_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_secret', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Ensure existing users are NOT forced to change password
UPDATE users SET must_change_password = FALSE WHERE must_change_password IS NULL;

-- ==========================================
-- PASSWORD & LOCKOUT CONFIG (per-tenant)
-- ==========================================
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_history_count', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_min_length', '8', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'max_failed_logins', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'lockout_duration_minutes', '15', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_reset_token_expiry_hours', '1', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Seed admin password into history
INSERT INTO password_history (user_id, password_hash)
SELECT user_id, password_hash FROM users WHERE username = 'admin'
ON CONFLICT DO NOTHING;
-- AI Assistant menu: managed in consolidated menu block above

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
-- Join key for the stagingToFact store_id/terminal_id fix-up UPDATEs, which match
-- fact rows to staging on (payment_date, arn). Without this the fix-ups hash the
-- whole staging table on every upload. See V2026_08_03_01.
CREATE INDEX IF NOT EXISTS idx_stg_txn_arn_date ON stg_trnx_raw (tenant_id, arn, payment_date);

-- Data Explorer menu: managed in consolidated menu block above
-- (saved_filter already created above)

-- ==========================================
-- DATA INTEGRATION HUB
-- ==========================================

-- External DB connections (per tenant)
CREATE TABLE IF NOT EXISTS integration_connection (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    db_type VARCHAR(50) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    db_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_password TEXT NOT NULL,
    timeout_seconds INTEGER DEFAULT 30,
    max_retries INTEGER DEFAULT 3,
    is_active BOOLEAN DEFAULT TRUE,
    last_test_at TIMESTAMP,
    last_test_status VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_conn_tenant ON integration_connection(tenant_id);

-- Report configs (per tenant, per type: MERCHANT or TRANSACTION)
CREATE TABLE IF NOT EXISTS integration_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connection_id BIGINT REFERENCES integration_connection(id),
    name VARCHAR(255) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    sql_text TEXT NOT NULL,
    column_mapping TEXT,
    description TEXT,
    param_schema TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    approved_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_report_tenant ON integration_report(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_report_type ON integration_report(tenant_id, report_type);

-- Schedule configs (independent per report per tenant)
CREATE TABLE IF NOT EXISTS integration_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_sched_tenant ON integration_schedule(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_sched_enabled ON integration_schedule(is_enabled);

-- Run history with retry tracking
CREATE TABLE IF NOT EXISTS integration_run_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    schedule_id BIGINT REFERENCES integration_schedule(id),
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_number INTEGER DEFAULT 1,
    max_retries INTEGER DEFAULT 3,
    rows_fetched INTEGER DEFAULT 0,
    rows_processed INTEGER DEFAULT 0,
    rows_failed INTEGER DEFAULT 0,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    error_message TEXT,
    date_range_from DATE,
    date_range_to DATE,
    duration_ms BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_run_tenant ON integration_run_log(tenant_id, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_intg_run_status ON integration_run_log(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_intg_run_report ON integration_run_log(report_id);

-- Menu entries for Data Integration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Integration Hub',       '/admin/integration',              'Cable',           'DATA INTEGRATION', 1),
('DB Connections',        '/admin/integration/connections',   'Database',        'DATA INTEGRATION', 2),
('Report Configs',        '/admin/integration/reports',       'FileCode',        'DATA INTEGRATION', 3),
('Schedules',             '/admin/integration/schedules',     'Clock',           'DATA INTEGRATION', 4),
('Run History',           '/admin/integration/runs',          'ScrollText',      'DATA INTEGRATION', 5)
ON CONFLICT (path) DO NOTHING;

-- Grant integration menus to SUPER_ADMIN and ADMIN groups
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'SUPER_ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

-- ==========================================
-- REPORT BUILDER & TEMPLATES
-- ==========================================

CREATE TABLE IF NOT EXISTS report_template (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    config_json TEXT NOT NULL,
    is_shared BOOLEAN DEFAULT FALSE,
    last_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_tpl_tenant ON report_template(tenant_id, user_id);

CREATE TABLE IF NOT EXISTS report_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    template_id BIGINT REFERENCES report_template(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    delivery_method VARCHAR(20) DEFAULT 'EMAIL',
    recipient_emails TEXT,
    export_format VARCHAR(10) DEFAULT 'EXCEL',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_sched_tenant ON report_schedule(tenant_id);

-- ==========================================
-- EMAIL CAMPAIGN SYSTEM
-- ==========================================

CREATE TABLE IF NOT EXISTS email_template_config (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_type VARCHAR(50) NOT NULL,
    subject_template VARCHAR(500) NOT NULL,
    body_html TEXT NOT NULL,
    body_text TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    is_default_for_type BOOLEAN DEFAULT FALSE,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_tpl_tenant ON email_template_config(tenant_id);

CREATE TABLE IF NOT EXISTS email_campaign (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_id BIGINT REFERENCES email_template_config(id),
    campaign_type VARCHAR(20) NOT NULL,
    recipient_filter_json TEXT,
    attachment_type VARCHAR(20) DEFAULT 'NONE',
    attachment_report_template_id BIGINT,
    statement_month VARCHAR(10),
    schedule_cron VARCHAR(100),
    schedule_timezone VARCHAR(50),
    status VARCHAR(20) DEFAULT 'DRAFT',
    total_recipients INTEGER DEFAULT 0,
    sent_count INTEGER DEFAULT 0,
    failed_count INTEGER DEFAULT 0,
    sent_at TIMESTAMP,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_camp_tenant ON email_campaign(tenant_id);
CREATE INDEX IF NOT EXISTS idx_email_camp_status ON email_campaign(tenant_id, status);

CREATE TABLE IF NOT EXISTS email_campaign_log (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES email_campaign(id),
    tenant_id BIGINT NOT NULL,
    merchant_id BIGINT,
    merchant_name VARCHAR(255),
    recipient_email VARCHAR(255),
    subject_rendered VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    sent_at TIMESTAMP,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_clog_campaign ON email_campaign_log(campaign_id);
CREATE INDEX IF NOT EXISTS idx_email_clog_tenant ON email_campaign_log(tenant_id, sent_at DESC);

-- Default email templates
INSERT INTO email_template_config (tenant_id, name, template_type, subject_template, body_html, is_active, is_default_for_type) VALUES
(1, 'Monthly Statement', 'STATEMENT',
 'Your {{month}} Performance Statement - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:#0f172a;color:#fff;padding:30px;text-align:center}.header h1{margin:0;font-size:24px}.content{padding:40px 30px}.greeting{font-size:18px;color:#1e293b;margin-bottom:20px}.card{background:#f1f5f9;border-radius:8px;padding:20px;margin-bottom:20px;border:1px solid #e2e8f0}.stats{display:flex;gap:20px;margin:20px 0}.stat{flex:1;text-align:center;padding:15px;background:#fff;border-radius:8px;border:1px solid #e2e8f0}.stat-value{font-size:22px;font-weight:700;color:#0f172a}.stat-label{font-size:12px;color:#64748b;text-transform:uppercase}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8;border-top:1px solid #e2e8f0}</style></head><body><div class="container"><div class="header"><h1>{{tenant_name}}</h1></div><div class="content"><div class="greeting">Dear {{contact_name}},</div><p>Your performance statement for <strong>{{month}}</strong> is now available.</p><div class="card"><div style="font-size:14px;color:#64748b;text-transform:uppercase;font-weight:600;margin-bottom:10px">Performance Summary</div><div class="stats"><div class="stat"><div class="stat-value">{{total_count}}</div><div class="stat-label">Transactions</div></div><div class="stat"><div class="stat-value">{{total_volume}}</div><div class="stat-label">Volume</div></div><div class="stat"><div class="stat-value">{{total_msf}}</div><div class="stat-label">MSF Revenue</div></div></div></div><p style="color:#64748b">Please find the detailed PDF report attached to this email.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.<br>This is an automated message.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Welcome Email', 'WELCOME',
 'Welcome to {{tenant_name}} - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:linear-gradient(135deg,#2563eb,#7c3aed);color:#fff;padding:40px;text-align:center}.header h1{margin:0;font-size:28px}.content{padding:40px 30px}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>Welcome!</h1></div><div class="content"><h2>Hello {{contact_name}},</h2><p>Welcome to <strong>{{tenant_name}}</strong>! We are excited to have <strong>{{merchant_name}}</strong> onboard.</p><p>Your Merchant ID is <strong>{{mid}}</strong>.</p><p>You can view your transaction data, performance analytics, and monthly statements through our portal.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Dormancy Alert', 'ALERT',
 'Action Required: {{merchant_name}} - No recent transactions',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden}.header{background:#dc2626;color:#fff;padding:30px;text-align:center}.content{padding:40px 30px}.alert-box{background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:20px;margin:20px 0}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>⚠ Activity Alert</h1></div><div class="content"><p>Dear Team,</p><div class="alert-box"><strong>{{merchant_name}}</strong> (MID: {{mid}}) has not processed transactions in <strong>{{days_since_last_txn}} days</strong>.</div><p>Location: {{city}}<br>Status: {{merchant_status}}<br>Stores: {{store_count}} | Terminals: {{terminal_count}}</p><p>Please follow up to ensure the merchant is still active.</p></div><div class="footer">&copy; 2026 {{tenant_name}}</div></div></body></html>',
 TRUE, TRUE)
ON CONFLICT DO NOTHING;

-- Menu entries for Email Campaign Hub
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Email Campaigns',  '/admin/email-campaigns',  'MailOpen',  'OPERATIONS', 4)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN') AND m.path = '/admin/email-campaigns'
ON CONFLICT DO NOTHING;

-- Menu entry for SSO Settings
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('SSO Settings',  '/admin/sso-settings',  'Shield',  'ADMINISTRATION', 5)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN') AND m.path = '/admin/sso-settings'
ON CONFLICT DO NOTHING;

-- Menu entry for Data Migration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Data Migration',  '/admin/data-migration',  'DatabaseZap',  'ADMINISTRATION', 7)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin') AND m.path = '/admin/data-migration'
ON CONFLICT DO NOTHING;

-- Menu entry for Sales Leaderboard
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Sales Leaderboard',  '/sales/leaderboard',  'Trophy',  'SALES', 2)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN', 'BUSINESS') AND m.path = '/sales/leaderboard'
ON CONFLICT DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- PDF Optimization — contact_email + email_queue
-- ═══════════════════════════════════════════════════════════

-- Add contact_email to dim_merchant for merchant report emailing
-- (Using ADD COLUMN IF NOT EXISTS — avoids DO $ blocks which break Spring ScriptUtils)
ALTER TABLE dim_merchant ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255);

-- Email queue table for async email processing
CREATE TABLE IF NOT EXISTS email_queue (
    id              BIGSERIAL PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(500),
    body            TEXT,
    attachment_path VARCHAR(1000),
    status          VARCHAR(20) DEFAULT 'PENDING',
    error_message   TEXT,
    retry_count     INT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    sent_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_email_queue_status ON email_queue(status);
CREATE INDEX IF NOT EXISTS idx_email_queue_pending ON email_queue(status, created_at) WHERE status = 'PENDING';

-- Refresh Token tracking (#14: rotation + revocation)
CREATE TABLE IF NOT EXISTS refresh_token (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    issued_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by     VARCHAR(128),
    user_agent      VARCHAR(500),
    ip_address      VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_username ON refresh_token(username);
CREATE INDEX IF NOT EXISTS idx_refresh_token_hash ON refresh_token(token_hash);

-- SSO State Tokens (#7: persist across restart)
CREATE TABLE IF NOT EXISTS sso_state_token (
    state_token     VARCHAR(100) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_sso_state_expires ON sso_state_token(expires_at);

-- ═══════════════════════════════════════════════════════════
-- Menu entries for Session 17 new admin screens
-- ═══════════════════════════════════════════════════════════

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Security Settings',  '/admin/security-settings',  'ShieldCheck',  'ADMINISTRATION', 6),
('Alerts & Notifications',  '/admin/alerts',  'BellRing',  'OPERATIONS', 5),
('API Management',  '/admin/api-management',  'Code',  'ADMINISTRATION', 8)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'SUPER_ADMIN', 'ADMIN')
  AND m.path IN ('/admin/security-settings', '/admin/alerts', '/admin/api-management')
ON CONFLICT DO NOTHING;


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

-- ==========================================
-- 2. COMPLETE MENU REGISTRY
-- Every menu here maps 1:1 to a route in App.jsx
-- No hardcoded menus in frontend — this is the single source of truth
-- ==========================================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
-- EXECUTIVE
('Dashboard',                '/dashboard',                        'LayoutDashboard', 'EXECUTIVE',      1),
('Executive Dashboard',      '/business/executive-dashboard-v2',  'Presentation',    'EXECUTIVE',      2),

-- MERCHANT MGT
('Merchant Universe',        '/merchants',                        'Store',           'MERCHANT MGT',   1),
('Transactions',             '/transactions',                     'List',            'MERCHANT MGT',   2),
('Merchant Summary',         '/merchant-summary',                 'Table',           'MERCHANT MGT',   3),
('Merchant Insight Hub',     '/merchant/insight-hub',             'PieChart',        'MERCHANT MGT',   4),
('Transaction Trends',       '/trends/hub',                       'Activity',        'MERCHANT MGT',   5),

-- BUSINESS
('Business Dashboard',       '/business/dashboard',               'LayoutGrid',      'BUSINESS',       0),
('Volume & Revenue',         '/business/volume-revenue',          'BarChart3',       'BUSINESS',       1),
('Merchant Financial',       '/business/merchant-financial',      'DollarSign',      'BUSINESS',       2),
('Performance Trends',       '/business/performance',             'TrendingUp',      'BUSINESS',       3),
('Debit & Prepaid Metrics',  '/business/debit-prepaid',           'CreditCard',      'BUSINESS',       4),
('Attrition Report',         '/business/attrition',               'TrendingDown',    'BUSINESS',       5),
('Zero Transaction Report',  '/business/zero-transaction',        'AlertTriangle',   'BUSINESS',       6),
('Merchant Growth Heatmap',  '/business/heatmap',                 'Grid',            'BUSINESS',       7),
('Daily Merchant Dashboard', '/business/daily-dashboard',         'Calendar',        'BUSINESS',       8),
('Merchant Analytics',       '/business/merchant-analytics',      'BarChart2',       'BUSINESS',       9),
('Merchant Comparison',      '/business/comparison',              'Scale',           'BUSINESS',      10),
('Report Manager',           '/business/report-manager',          'FileText',        'BUSINESS',      11),
('Opportunity Intelligence', '/business/opportunity',             'Target',          'BUSINESS',      12),
('Group Reports',            '/business/groups',                  'FolderKanban',    'BUSINESS',      13),
('Data Explorer',            '/explorer',                         'Compass',         'BUSINESS',      14),
('AI Assistant',             '/ai-assistant',                     'BrainCircuit',    'BUSINESS',      15),

-- SALES
('Sales Team Management',    '/sales/team-management',            'Users',           'SALES',          1),

-- FINANCE
('Finance Dashboard',        '/finance/dashboard',                'PieChart',        'FINANCE',        1),
('Finance Summary',          '/finance/summary',                  'BookOpen',        'FINANCE',        2),
('Finance Lists',            '/finance/lists',                    'ClipboardList',   'FINANCE',        3),

-- OPERATIONS
('Upload Files',             '/upload',                           'Upload',          'OPERATIONS',     1),
('Server File Processor',    '/ops/server-file',                  'HardDrive',       'OPERATIONS',     2),
('Batch Logs',               '/ops/batch-logs',                   'Activity',        'OPERATIONS',     3),
('Email Manager',            '/business/emails',                  'Mail',            'OPERATIONS',     4),

-- ADMINISTRATION
('User Management',          '/users',                            'Users',           'ADMINISTRATION', 1),
('Bank Setup',               '/tenants',                          'Building',        'ADMINISTRATION', 2),
('Group Management',         '/admin/groups',                     'Shield',          'ADMINISTRATION', 3),
('SMTP Settings',            '/admin/smtp-settings',              'Settings',        'ADMINISTRATION', 4),
('Audit Logs',               '/admin/audit-logs',                 'ScrollText',      'ADMINISTRATION', 5),
('Backup & Restore',         '/admin/backups',                    'Database',        'ADMINISTRATION', 6)
ON CONFLICT (path) DO NOTHING;

-- ==========================================
-- 3. GROUP → MENU ASSIGNMENTS (RBAC)
-- ==========================================

-- Super Admin: ALL menus
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
ON CONFLICT DO NOTHING;

-- Bank Admin: Everything except sensitive admin pages
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Bank Admin'
  AND m.path NOT IN ('/admin/groups', '/admin/smtp-settings', '/admin/audit-logs', '/admin/backups')
ON CONFLICT DO NOTHING;

-- Business User: Executive + Business + Merchant Mgt + Sales
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Business User'
  AND m.category IN ('EXECUTIVE', 'BUSINESS', 'MERCHANT MGT', 'SALES')
ON CONFLICT DO NOTHING;

-- Finance User: Dashboard + Finance + Merchant Mgt
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Finance User'
  AND (m.category IN ('FINANCE', 'MERCHANT MGT') OR m.path = '/dashboard')
ON CONFLICT DO NOTHING;

-- Ops User: Dashboard + Operations + Backup
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Ops User'
  AND (m.category = 'OPERATIONS' OR m.path IN ('/dashboard', '/admin/backups'))
ON CONFLICT DO NOTHING;

-- 1.2 Tenant (The Core Institution/Bank Unit)
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
    last_login TIMESTAMP,
    -- Password Management
    must_change_password BOOLEAN DEFAULT FALSE,
    password_changed_at TIMESTAMP,
    -- Account Lockout
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMP,
    last_failed_login TIMESTAMP
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
    username VARCHAR(50),
    action_type VARCHAR(50), -- LOGIN, EXPORT, BATCH_RUN
    details TEXT,
    ip_address VARCHAR(45),
    event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    http_method VARCHAR(10),
    endpoint VARCHAR(255),
    status_code INT,
    user_agent VARCHAR(500),
    category VARCHAR(50),
    entity_type VARCHAR(50),
    entity_id VARCHAR(100),
    duration_ms BIGINT
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
-- #21: Use MONTHLY partitions for fact_transaction (aligned with PartitionMaintenanceService)
-- Yearly partitions removed to avoid overlap with monthly partitions.
-- PartitionMaintenanceService.ensurePartitionsForYear() creates monthly partitions
-- (e.g. fact_transaction_y2025m01 ... fact_transaction_y2025m12) at application startup.
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
    total_msf DECIMAL(21, 4),
    total_interchange DECIMAL(19, 2),
    total_scheme_fee DECIMAL(19, 2), 
    total_margin DECIMAL(19, 2),
    
    total_debit_prepaid_volume DECIMAL(19, 2) DEFAULT 0,
    total_credit_volume DECIMAL(19, 2) DEFAULT 0,
    sales_user_id VARCHAR(50),
    
    unique_customer_count BIGINT DEFAULT 0,
    top_spending_customer_id VARCHAR(50),
    top_spending_amount DECIMAL(19, 2),
    
    -- Base Currency Volume (Store Base Currency Amount for merchant-facing PDF)
    total_base_volume DECIMAL(19, 2) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4),
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    dom_debit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_debit_optin DECIMAL(19, 2) DEFAULT 0, -- Opt-in Volume (DCC)

    -- Domestic Credit
    dom_credit_cnt BIGINT DEFAULT 0,
    dom_credit_vol DECIMAL(19, 2) DEFAULT 0,
    dom_credit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_credit_optin DECIMAL(19, 2) DEFAULT 0,

    -- International
    int_cnt BIGINT DEFAULT 0,
    int_vol DECIMAL(19, 2) DEFAULT 0,
    int_msf DECIMAL(21, 4) DEFAULT 0,
    int_optin DECIMAL(19, 2) DEFAULT 0,

    -- Totals
    total_vol DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(21, 4) DEFAULT 0,

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
    total_msf DECIMAL(21, 4) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    volatility_index DECIMAL(19, 4),
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
INSERT INTO users (username, password_hash, email, role, is_active, must_change_password) VALUES 
('admin', '{noop}password', 'admin@acquira.com', 'ROLE_SUPER_ADMIN', true, false)
ON CONFLICT (username) DO UPDATE 
SET password_hash = EXCLUDED.password_hash, must_change_password = FALSE, role = 'ROLE_SUPER_ADMIN';

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
-- (V2026_08_01_01) idx_fact_txn_card removed: no report query reads fact by card_number (card lookups use sum_monthly_card); at 300M+ rows/yr it was the most expensive index to maintain. Recreating it here would undo that migration's DROP.

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
-- SALES TEAM MAPPING & ASSIGNMENT
-- ==========================================
CREATE TABLE IF NOT EXISTS sales_team_mapping (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    team_lead_name  VARCHAR(100) NOT NULL,
    team_lead_email VARCHAR(150) NOT NULL,
    is_default      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_team_tenant_email UNIQUE (tenant_id, team_lead_email)
);
CREATE INDEX IF NOT EXISTS idx_sales_team_tenant ON sales_team_mapping(tenant_id);

CREATE TABLE IF NOT EXISTS sales_user_assignment (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    sales_user_id   VARCHAR(100) NOT NULL,
    team_lead_id    BIGINT NOT NULL REFERENCES sales_team_mapping(id),
    assigned_at     TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_user_tenant UNIQUE (tenant_id, sales_user_id)
);
CREATE INDEX IF NOT EXISTS idx_sales_assign_tenant ON sales_user_assignment(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_assign_lead ON sales_user_assignment(team_lead_id);

-- Default Team Lead (auto-assign unmapped sales users to this lead)
INSERT INTO sales_team_mapping (tenant_id, team_lead_name, team_lead_email, is_default)
VALUES (1, 'Default Team Lead', 'default-lead@acquira.com', true)
ON CONFLICT (tenant_id, team_lead_email) DO UPDATE SET is_default = true;

-- ==========================================
-- PASSWORD HISTORY
-- ==========================================
CREATE TABLE IF NOT EXISTS password_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_password_history_user ON password_history(user_id);

-- ==========================================
-- PASSWORD RESET TOKENS
-- ==========================================
CREATE TABLE IF NOT EXISTS password_reset_token (
    token_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_reset_token ON password_reset_token(token);

-- ==========================================
-- ALTER existing users table (for upgrades)
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts INT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_failed_login TIMESTAMP;

-- ==========================================
-- SSO & ACCESS REQUEST SUPPORT
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_provider VARCHAR(20);       -- 'MICROSOFT', NULL
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_id VARCHAR(255);            -- Azure AD Object ID
ALTER TABLE users ADD COLUMN IF NOT EXISTS approval_status VARCHAR(20) DEFAULT 'APPROVED';
    -- APPROVED (normal users), PENDING (SSO requests), REJECTED
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR(150);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS role_in_tenant VARCHAR(50);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS is_default_tenant BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS access_request (
    request_id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(150),
    sso_provider VARCHAR(20),
    sso_id VARCHAR(255),
    requested_tenant_id INT REFERENCES tenant(tenant_id),
    message TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    reviewed_by BIGINT REFERENCES users(user_id),
    reviewed_at TIMESTAMP,
    review_notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- SSO Configuration per tenant (admin toggle)
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_enabled', 'false', 'BOOLEAN'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_provider', 'MICROSOFT', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_tenant_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_secret', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Ensure existing users are NOT forced to change password
UPDATE users SET must_change_password = FALSE WHERE must_change_password IS NULL;

-- ==========================================
-- PASSWORD & LOCKOUT CONFIG (per-tenant)
-- ==========================================
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_history_count', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_min_length', '8', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'max_failed_logins', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'lockout_duration_minutes', '15', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_reset_token_expiry_hours', '1', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Seed admin password into history
INSERT INTO password_history (user_id, password_hash)
SELECT user_id, password_hash FROM users WHERE username = 'admin'
ON CONFLICT DO NOTHING;
-- AI Assistant menu: managed in consolidated menu block above

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
-- Join key for the stagingToFact store_id/terminal_id fix-up UPDATEs, which match
-- fact rows to staging on (payment_date, arn). Without this the fix-ups hash the
-- whole staging table on every upload. See V2026_08_03_01.
CREATE INDEX IF NOT EXISTS idx_stg_txn_arn_date ON stg_trnx_raw (tenant_id, arn, payment_date);

-- Data Explorer menu: managed in consolidated menu block above
-- (saved_filter already created above)

-- ==========================================
-- DATA INTEGRATION HUB
-- ==========================================

-- External DB connections (per tenant)
CREATE TABLE IF NOT EXISTS integration_connection (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    db_type VARCHAR(50) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    db_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_password TEXT NOT NULL,
    timeout_seconds INTEGER DEFAULT 30,
    max_retries INTEGER DEFAULT 3,
    is_active BOOLEAN DEFAULT TRUE,
    last_test_at TIMESTAMP,
    last_test_status VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_conn_tenant ON integration_connection(tenant_id);

-- Report configs (per tenant, per type: MERCHANT or TRANSACTION)
CREATE TABLE IF NOT EXISTS integration_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connection_id BIGINT REFERENCES integration_connection(id),
    name VARCHAR(255) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    sql_text TEXT NOT NULL,
    column_mapping TEXT,
    description TEXT,
    param_schema TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    approved_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_report_tenant ON integration_report(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_report_type ON integration_report(tenant_id, report_type);

-- Schedule configs (independent per report per tenant)
CREATE TABLE IF NOT EXISTS integration_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_sched_tenant ON integration_schedule(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_sched_enabled ON integration_schedule(is_enabled);

-- Run history with retry tracking
CREATE TABLE IF NOT EXISTS integration_run_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    schedule_id BIGINT REFERENCES integration_schedule(id),
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_number INTEGER DEFAULT 1,
    max_retries INTEGER DEFAULT 3,
    rows_fetched INTEGER DEFAULT 0,
    rows_processed INTEGER DEFAULT 0,
    rows_failed INTEGER DEFAULT 0,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    error_message TEXT,
    date_range_from DATE,
    date_range_to DATE,
    duration_ms BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_run_tenant ON integration_run_log(tenant_id, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_intg_run_status ON integration_run_log(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_intg_run_report ON integration_run_log(report_id);

-- Menu entries for Data Integration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Integration Hub',       '/admin/integration',              'Cable',           'DATA INTEGRATION', 1),
('DB Connections',        '/admin/integration/connections',   'Database',        'DATA INTEGRATION', 2),
('Report Configs',        '/admin/integration/reports',       'FileCode',        'DATA INTEGRATION', 3),
('Schedules',             '/admin/integration/schedules',     'Clock',           'DATA INTEGRATION', 4),
('Run History',           '/admin/integration/runs',          'ScrollText',      'DATA INTEGRATION', 5)
ON CONFLICT (path) DO NOTHING;

-- Grant integration menus to SUPER_ADMIN and ADMIN groups
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'SUPER_ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

-- ==========================================
-- REPORT BUILDER & TEMPLATES
-- ==========================================

CREATE TABLE IF NOT EXISTS report_template (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    config_json TEXT NOT NULL,
    is_shared BOOLEAN DEFAULT FALSE,
    last_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_tpl_tenant ON report_template(tenant_id, user_id);

CREATE TABLE IF NOT EXISTS report_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    template_id BIGINT REFERENCES report_template(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    delivery_method VARCHAR(20) DEFAULT 'EMAIL',
    recipient_emails TEXT,
    export_format VARCHAR(10) DEFAULT 'EXCEL',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_sched_tenant ON report_schedule(tenant_id);

-- ==========================================
-- EMAIL CAMPAIGN SYSTEM
-- ==========================================

CREATE TABLE IF NOT EXISTS email_template_config (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_type VARCHAR(50) NOT NULL,
    subject_template VARCHAR(500) NOT NULL,
    body_html TEXT NOT NULL,
    body_text TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    is_default_for_type BOOLEAN DEFAULT FALSE,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_tpl_tenant ON email_template_config(tenant_id);

CREATE TABLE IF NOT EXISTS email_campaign (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_id BIGINT REFERENCES email_template_config(id),
    campaign_type VARCHAR(20) NOT NULL,
    recipient_filter_json TEXT,
    attachment_type VARCHAR(20) DEFAULT 'NONE',
    attachment_report_template_id BIGINT,
    statement_month VARCHAR(10),
    schedule_cron VARCHAR(100),
    schedule_timezone VARCHAR(50),
    status VARCHAR(20) DEFAULT 'DRAFT',
    total_recipients INTEGER DEFAULT 0,
    sent_count INTEGER DEFAULT 0,
    failed_count INTEGER DEFAULT 0,
    sent_at TIMESTAMP,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_camp_tenant ON email_campaign(tenant_id);
CREATE INDEX IF NOT EXISTS idx_email_camp_status ON email_campaign(tenant_id, status);

CREATE TABLE IF NOT EXISTS email_campaign_log (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES email_campaign(id),
    tenant_id BIGINT NOT NULL,
    merchant_id BIGINT,
    merchant_name VARCHAR(255),
    recipient_email VARCHAR(255),
    subject_rendered VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    sent_at TIMESTAMP,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_clog_campaign ON email_campaign_log(campaign_id);
CREATE INDEX IF NOT EXISTS idx_email_clog_tenant ON email_campaign_log(tenant_id, sent_at DESC);

-- Default email templates
INSERT INTO email_template_config (tenant_id, name, template_type, subject_template, body_html, is_active, is_default_for_type) VALUES
(1, 'Monthly Statement', 'STATEMENT',
 'Your {{month}} Performance Statement - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:#0f172a;color:#fff;padding:30px;text-align:center}.header h1{margin:0;font-size:24px}.content{padding:40px 30px}.greeting{font-size:18px;color:#1e293b;margin-bottom:20px}.card{background:#f1f5f9;border-radius:8px;padding:20px;margin-bottom:20px;border:1px solid #e2e8f0}.stats{display:flex;gap:20px;margin:20px 0}.stat{flex:1;text-align:center;padding:15px;background:#fff;border-radius:8px;border:1px solid #e2e8f0}.stat-value{font-size:22px;font-weight:700;color:#0f172a}.stat-label{font-size:12px;color:#64748b;text-transform:uppercase}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8;border-top:1px solid #e2e8f0}</style></head><body><div class="container"><div class="header"><h1>{{tenant_name}}</h1></div><div class="content"><div class="greeting">Dear {{contact_name}},</div><p>Your performance statement for <strong>{{month}}</strong> is now available.</p><div class="card"><div style="font-size:14px;color:#64748b;text-transform:uppercase;font-weight:600;margin-bottom:10px">Performance Summary</div><div class="stats"><div class="stat"><div class="stat-value">{{total_count}}</div><div class="stat-label">Transactions</div></div><div class="stat"><div class="stat-value">{{total_volume}}</div><div class="stat-label">Volume</div></div><div class="stat"><div class="stat-value">{{total_msf}}</div><div class="stat-label">MSF Revenue</div></div></div></div><p style="color:#64748b">Please find the detailed PDF report attached to this email.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.<br>This is an automated message.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Welcome Email', 'WELCOME',
 'Welcome to {{tenant_name}} - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:linear-gradient(135deg,#2563eb,#7c3aed);color:#fff;padding:40px;text-align:center}.header h1{margin:0;font-size:28px}.content{padding:40px 30px}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>Welcome!</h1></div><div class="content"><h2>Hello {{contact_name}},</h2><p>Welcome to <strong>{{tenant_name}}</strong>! We are excited to have <strong>{{merchant_name}}</strong> onboard.</p><p>Your Merchant ID is <strong>{{mid}}</strong>.</p><p>You can view your transaction data, performance analytics, and monthly statements through our portal.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Dormancy Alert', 'ALERT',
 'Action Required: {{merchant_name}} - No recent transactions',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden}.header{background:#dc2626;color:#fff;padding:30px;text-align:center}.content{padding:40px 30px}.alert-box{background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:20px;margin:20px 0}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>⚠ Activity Alert</h1></div><div class="content"><p>Dear Team,</p><div class="alert-box"><strong>{{merchant_name}}</strong> (MID: {{mid}}) has not processed transactions in <strong>{{days_since_last_txn}} days</strong>.</div><p>Location: {{city}}<br>Status: {{merchant_status}}<br>Stores: {{store_count}} | Terminals: {{terminal_count}}</p><p>Please follow up to ensure the merchant is still active.</p></div><div class="footer">&copy; 2026 {{tenant_name}}</div></div></body></html>',
 TRUE, TRUE)
ON CONFLICT DO NOTHING;

-- Menu entries for Email Campaign Hub
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Email Campaigns',  '/admin/email-campaigns',  'MailOpen',  'OPERATIONS', 4)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN') AND m.path = '/admin/email-campaigns'
ON CONFLICT DO NOTHING;

-- Menu entry for SSO Settings
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('SSO Settings',  '/admin/sso-settings',  'Shield',  'ADMINISTRATION', 5)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN') AND m.path = '/admin/sso-settings'
ON CONFLICT DO NOTHING;

-- Menu entry for Data Migration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Data Migration',  '/admin/data-migration',  'DatabaseZap',  'ADMINISTRATION', 7)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin') AND m.path = '/admin/data-migration'
ON CONFLICT DO NOTHING;

-- Menu entry for Sales Leaderboard
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Sales Leaderboard',  '/sales/leaderboard',  'Trophy',  'SALES', 2)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN', 'BUSINESS') AND m.path = '/sales/leaderboard'
ON CONFLICT DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- PDF Optimization — contact_email + email_queue
-- ═══════════════════════════════════════════════════════════

-- Add contact_email to dim_merchant for merchant report emailing
-- (Using ADD COLUMN IF NOT EXISTS — avoids DO $ blocks which break Spring ScriptUtils)
ALTER TABLE dim_merchant ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255);

-- Email queue table for async email processing
CREATE TABLE IF NOT EXISTS email_queue (
    id              BIGSERIAL PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(500),
    body            TEXT,
    attachment_path VARCHAR(1000),
    status          VARCHAR(20) DEFAULT 'PENDING',
    error_message   TEXT,
    retry_count     INT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    sent_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_email_queue_status ON email_queue(status);
CREATE INDEX IF NOT EXISTS idx_email_queue_pending ON email_queue(status, created_at) WHERE status = 'PENDING';

-- Refresh Token tracking (#14: rotation + revocation)
CREATE TABLE IF NOT EXISTS refresh_token (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    issued_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by     VARCHAR(128),
    user_agent      VARCHAR(500),
    ip_address      VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_username ON refresh_token(username);
CREATE INDEX IF NOT EXISTS idx_refresh_token_hash ON refresh_token(token_hash);

-- SSO State Tokens (#7: persist across restart)
CREATE TABLE IF NOT EXISTS sso_state_token (
    state_token     VARCHAR(100) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_sso_state_expires ON sso_state_token(expires_at);

-- ═══════════════════════════════════════════════════════════
-- Menu entries for Session 17 new admin screens
-- ═══════════════════════════════════════════════════════════

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Security Settings',  '/admin/security-settings',  'ShieldCheck',  'ADMINISTRATION', 6),
('Alerts & Notifications',  '/admin/alerts',  'BellRing',  'OPERATIONS', 5),
('API Management',  '/admin/api-management',  'Code',  'ADMINISTRATION', 8)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'SUPER_ADMIN', 'ADMIN')
  AND m.path IN ('/admin/security-settings', '/admin/alerts', '/admin/api-management')
ON CONFLICT DO NOTHING;


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

-- ==========================================
-- 2. COMPLETE MENU REGISTRY
-- Every menu here maps 1:1 to a route in App.jsx
-- No hardcoded menus in frontend — this is the single source of truth
-- ==========================================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
-- EXECUTIVE
('Dashboard',                '/dashboard',                        'LayoutDashboard', 'EXECUTIVE',      1),
('Executive Dashboard',      '/business/executive-dashboard-v2',  'Presentation',    'EXECUTIVE',      2),

-- MERCHANT MGT
('Merchant Universe',        '/merchants',                        'Store',           'MERCHANT MGT',   1),
('Transactions',             '/transactions',                     'List',            'MERCHANT MGT',   2),
('Merchant Summary',         '/merchant-summary',                 'Table',           'MERCHANT MGT',   3),
('Merchant Insight Hub',     '/merchant/insight-hub',             'PieChart',        'MERCHANT MGT',   4),
('Transaction Trends',       '/trends/hub',                       'Activity',        'MERCHANT MGT',   5),

-- BUSINESS
('Business Dashboard',       '/business/dashboard',               'LayoutGrid',      'BUSINESS',       0),
('Volume & Revenue',         '/business/volume-revenue',          'BarChart3',       'BUSINESS',       1),
('Merchant Financial',       '/business/merchant-financial',      'DollarSign',      'BUSINESS',       2),
('Performance Trends',       '/business/performance',             'TrendingUp',      'BUSINESS',       3),
('Debit & Prepaid Metrics',  '/business/debit-prepaid',           'CreditCard',      'BUSINESS',       4),
('Attrition Report',         '/business/attrition',               'TrendingDown',    'BUSINESS',       5),
('Zero Transaction Report',  '/business/zero-transaction',        'AlertTriangle',   'BUSINESS',       6),
('Merchant Growth Heatmap',  '/business/heatmap',                 'Grid',            'BUSINESS',       7),
('Daily Merchant Dashboard', '/business/daily-dashboard',         'Calendar',        'BUSINESS',       8),
('Merchant Analytics',       '/business/merchant-analytics',      'BarChart2',       'BUSINESS',       9),
('Merchant Comparison',      '/business/comparison',              'Scale',           'BUSINESS',      10),
('Report Manager',           '/business/report-manager',          'FileText',        'BUSINESS',      11),
('Opportunity Intelligence', '/business/opportunity',             'Target',          'BUSINESS',      12),
('Group Reports',            '/business/groups',                  'FolderKanban',    'BUSINESS',      13),
('Data Explorer',            '/explorer',                         'Compass',         'BUSINESS',      14),
('AI Assistant',             '/ai-assistant',                     'BrainCircuit',    'BUSINESS',      15),

-- SALES
('Sales Team Management',    '/sales/team-management',            'Users',           'SALES',          1),

-- FINANCE
('Finance Dashboard',        '/finance/dashboard',                'PieChart',        'FINANCE',        1),
('Finance Summary',          '/finance/summary',                  'BookOpen',        'FINANCE',        2),
('Finance Lists',            '/finance/lists',                    'ClipboardList',   'FINANCE',        3),

-- OPERATIONS
('Upload Files',             '/upload',                           'Upload',          'OPERATIONS',     1),
('Server File Processor',    '/ops/server-file',                  'HardDrive',       'OPERATIONS',     2),
('Batch Logs',               '/ops/batch-logs',                   'Activity',        'OPERATIONS',     3),
('Email Manager',            '/business/emails',                  'Mail',            'OPERATIONS',     4),

-- ADMINISTRATION
('User Management',          '/users',                            'Users',           'ADMINISTRATION', 1),
('Bank Setup',               '/tenants',                          'Building',        'ADMINISTRATION', 2),
('Group Management',         '/admin/groups',                     'Shield',          'ADMINISTRATION', 3),
('SMTP Settings',            '/admin/smtp-settings',              'Settings',        'ADMINISTRATION', 4),
('Audit Logs',               '/admin/audit-logs',                 'ScrollText',      'ADMINISTRATION', 5),
('Backup & Restore',         '/admin/backups',                    'Database',        'ADMINISTRATION', 6)
ON CONFLICT (path) DO NOTHING;

-- ==========================================
-- 3. GROUP → MENU ASSIGNMENTS (RBAC)
-- ==========================================

-- Super Admin: ALL menus
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
ON CONFLICT DO NOTHING;

-- Bank Admin: Everything except sensitive admin pages
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Bank Admin'
  AND m.path NOT IN ('/admin/groups', '/admin/smtp-settings', '/admin/audit-logs', '/admin/backups')
ON CONFLICT DO NOTHING;

-- Business User: Executive + Business + Merchant Mgt + Sales
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Business User'
  AND m.category IN ('EXECUTIVE', 'BUSINESS', 'MERCHANT MGT', 'SALES')
ON CONFLICT DO NOTHING;

-- Finance User: Dashboard + Finance + Merchant Mgt
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Finance User'
  AND (m.category IN ('FINANCE', 'MERCHANT MGT') OR m.path = '/dashboard')
ON CONFLICT DO NOTHING;

-- Ops User: Dashboard + Operations + Backup
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Ops User'
  AND (m.category = 'OPERATIONS' OR m.path IN ('/dashboard', '/admin/backups'))
ON CONFLICT DO NOTHING;

-- 1.2 Tenant (The Core Institution/Bank Unit)
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
    last_login TIMESTAMP,
    -- Password Management
    must_change_password BOOLEAN DEFAULT FALSE,
    password_changed_at TIMESTAMP,
    -- Account Lockout
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMP,
    last_failed_login TIMESTAMP
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
    username VARCHAR(50),
    action_type VARCHAR(50), -- LOGIN, EXPORT, BATCH_RUN
    details TEXT,
    ip_address VARCHAR(45),
    event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    http_method VARCHAR(10),
    endpoint VARCHAR(255),
    status_code INT,
    user_agent VARCHAR(500),
    category VARCHAR(50),
    entity_type VARCHAR(50),
    entity_id VARCHAR(100),
    duration_ms BIGINT
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
-- #21: Use MONTHLY partitions for fact_transaction (aligned with PartitionMaintenanceService)
-- Yearly partitions removed to avoid overlap with monthly partitions.
-- PartitionMaintenanceService.ensurePartitionsForYear() creates monthly partitions
-- (e.g. fact_transaction_y2025m01 ... fact_transaction_y2025m12) at application startup.
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
    total_msf DECIMAL(21, 4),
    total_interchange DECIMAL(19, 2),
    total_scheme_fee DECIMAL(19, 2), 
    total_margin DECIMAL(19, 2),
    
    total_debit_prepaid_volume DECIMAL(19, 2) DEFAULT 0,
    total_credit_volume DECIMAL(19, 2) DEFAULT 0,
    sales_user_id VARCHAR(50),
    
    unique_customer_count BIGINT DEFAULT 0,
    top_spending_customer_id VARCHAR(50),
    top_spending_amount DECIMAL(19, 2),
    
    -- Base Currency Volume (Store Base Currency Amount for merchant-facing PDF)
    total_base_volume DECIMAL(19, 2) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4),
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    dom_debit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_debit_optin DECIMAL(19, 2) DEFAULT 0, -- Opt-in Volume (DCC)

    -- Domestic Credit
    dom_credit_cnt BIGINT DEFAULT 0,
    dom_credit_vol DECIMAL(19, 2) DEFAULT 0,
    dom_credit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_credit_optin DECIMAL(19, 2) DEFAULT 0,

    -- International
    int_cnt BIGINT DEFAULT 0,
    int_vol DECIMAL(19, 2) DEFAULT 0,
    int_msf DECIMAL(21, 4) DEFAULT 0,
    int_optin DECIMAL(19, 2) DEFAULT 0,

    -- Totals
    total_vol DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(21, 4) DEFAULT 0,

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
    total_msf DECIMAL(21, 4) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    volatility_index DECIMAL(19, 4),
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
INSERT INTO users (username, password_hash, email, role, is_active, must_change_password) VALUES 
('admin', '{noop}password', 'admin@acquira.com', 'ROLE_SUPER_ADMIN', true, false)
ON CONFLICT (username) DO UPDATE 
SET password_hash = EXCLUDED.password_hash, must_change_password = FALSE, role = 'ROLE_SUPER_ADMIN';

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
-- (V2026_08_01_01) idx_fact_txn_card removed: no report query reads fact by card_number (card lookups use sum_monthly_card); at 300M+ rows/yr it was the most expensive index to maintain. Recreating it here would undo that migration's DROP.

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
-- SALES TEAM MAPPING & ASSIGNMENT
-- ==========================================
CREATE TABLE IF NOT EXISTS sales_team_mapping (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    team_lead_name  VARCHAR(100) NOT NULL,
    team_lead_email VARCHAR(150) NOT NULL,
    is_default      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_team_tenant_email UNIQUE (tenant_id, team_lead_email)
);
CREATE INDEX IF NOT EXISTS idx_sales_team_tenant ON sales_team_mapping(tenant_id);

CREATE TABLE IF NOT EXISTS sales_user_assignment (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    sales_user_id   VARCHAR(100) NOT NULL,
    team_lead_id    BIGINT NOT NULL REFERENCES sales_team_mapping(id),
    assigned_at     TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_user_tenant UNIQUE (tenant_id, sales_user_id)
);
CREATE INDEX IF NOT EXISTS idx_sales_assign_tenant ON sales_user_assignment(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_assign_lead ON sales_user_assignment(team_lead_id);

-- Default Team Lead (auto-assign unmapped sales users to this lead)
INSERT INTO sales_team_mapping (tenant_id, team_lead_name, team_lead_email, is_default)
VALUES (1, 'Default Team Lead', 'default-lead@acquira.com', true)
ON CONFLICT (tenant_id, team_lead_email) DO UPDATE SET is_default = true;

-- ==========================================
-- PASSWORD HISTORY
-- ==========================================
CREATE TABLE IF NOT EXISTS password_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_password_history_user ON password_history(user_id);

-- ==========================================
-- PASSWORD RESET TOKENS
-- ==========================================
CREATE TABLE IF NOT EXISTS password_reset_token (
    token_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_reset_token ON password_reset_token(token);

-- ==========================================
-- ALTER existing users table (for upgrades)
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts INT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_failed_login TIMESTAMP;

-- ==========================================
-- SSO & ACCESS REQUEST SUPPORT
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_provider VARCHAR(20);       -- 'MICROSOFT', NULL
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_id VARCHAR(255);            -- Azure AD Object ID
ALTER TABLE users ADD COLUMN IF NOT EXISTS approval_status VARCHAR(20) DEFAULT 'APPROVED';
    -- APPROVED (normal users), PENDING (SSO requests), REJECTED
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR(150);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS role_in_tenant VARCHAR(50);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS is_default_tenant BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS access_request (
    request_id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(150),
    sso_provider VARCHAR(20),
    sso_id VARCHAR(255),
    requested_tenant_id INT REFERENCES tenant(tenant_id),
    message TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    reviewed_by BIGINT REFERENCES users(user_id),
    reviewed_at TIMESTAMP,
    review_notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- SSO Configuration per tenant (admin toggle)
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_enabled', 'false', 'BOOLEAN'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_provider', 'MICROSOFT', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_tenant_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_secret', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Ensure existing users are NOT forced to change password
UPDATE users SET must_change_password = FALSE WHERE must_change_password IS NULL;

-- ==========================================
-- PASSWORD & LOCKOUT CONFIG (per-tenant)
-- ==========================================
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_history_count', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_min_length', '8', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'max_failed_logins', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'lockout_duration_minutes', '15', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_reset_token_expiry_hours', '1', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Seed admin password into history
INSERT INTO password_history (user_id, password_hash)
SELECT user_id, password_hash FROM users WHERE username = 'admin'
ON CONFLICT DO NOTHING;
-- AI Assistant menu: managed in consolidated menu block above

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
-- Join key for the stagingToFact store_id/terminal_id fix-up UPDATEs, which match
-- fact rows to staging on (payment_date, arn). Without this the fix-ups hash the
-- whole staging table on every upload. See V2026_08_03_01.
CREATE INDEX IF NOT EXISTS idx_stg_txn_arn_date ON stg_trnx_raw (tenant_id, arn, payment_date);

-- Data Explorer menu: managed in consolidated menu block above
-- (saved_filter already created above)

-- ==========================================
-- DATA INTEGRATION HUB
-- ==========================================

-- External DB connections (per tenant)
CREATE TABLE IF NOT EXISTS integration_connection (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    db_type VARCHAR(50) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    db_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_password TEXT NOT NULL,
    timeout_seconds INTEGER DEFAULT 30,
    max_retries INTEGER DEFAULT 3,
    is_active BOOLEAN DEFAULT TRUE,
    last_test_at TIMESTAMP,
    last_test_status VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_conn_tenant ON integration_connection(tenant_id);

-- Report configs (per tenant, per type: MERCHANT or TRANSACTION)
CREATE TABLE IF NOT EXISTS integration_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connection_id BIGINT REFERENCES integration_connection(id),
    name VARCHAR(255) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    sql_text TEXT NOT NULL,
    column_mapping TEXT,
    description TEXT,
    param_schema TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    approved_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_report_tenant ON integration_report(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_report_type ON integration_report(tenant_id, report_type);

-- Schedule configs (independent per report per tenant)
CREATE TABLE IF NOT EXISTS integration_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_sched_tenant ON integration_schedule(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_sched_enabled ON integration_schedule(is_enabled);

-- Run history with retry tracking
CREATE TABLE IF NOT EXISTS integration_run_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    schedule_id BIGINT REFERENCES integration_schedule(id),
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_number INTEGER DEFAULT 1,
    max_retries INTEGER DEFAULT 3,
    rows_fetched INTEGER DEFAULT 0,
    rows_processed INTEGER DEFAULT 0,
    rows_failed INTEGER DEFAULT 0,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    error_message TEXT,
    date_range_from DATE,
    date_range_to DATE,
    duration_ms BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_run_tenant ON integration_run_log(tenant_id, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_intg_run_status ON integration_run_log(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_intg_run_report ON integration_run_log(report_id);

-- Menu entries for Data Integration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Integration Hub',       '/admin/integration',              'Cable',           'DATA INTEGRATION', 1),
('DB Connections',        '/admin/integration/connections',   'Database',        'DATA INTEGRATION', 2),
('Report Configs',        '/admin/integration/reports',       'FileCode',        'DATA INTEGRATION', 3),
('Schedules',             '/admin/integration/schedules',     'Clock',           'DATA INTEGRATION', 4),
('Run History',           '/admin/integration/runs',          'ScrollText',      'DATA INTEGRATION', 5)
ON CONFLICT (path) DO NOTHING;

-- Grant integration menus to SUPER_ADMIN and ADMIN groups
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'SUPER_ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

-- ==========================================
-- REPORT BUILDER & TEMPLATES
-- ==========================================

CREATE TABLE IF NOT EXISTS report_template (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    config_json TEXT NOT NULL,
    is_shared BOOLEAN DEFAULT FALSE,
    last_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_tpl_tenant ON report_template(tenant_id, user_id);

CREATE TABLE IF NOT EXISTS report_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    template_id BIGINT REFERENCES report_template(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    delivery_method VARCHAR(20) DEFAULT 'EMAIL',
    recipient_emails TEXT,
    export_format VARCHAR(10) DEFAULT 'EXCEL',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_sched_tenant ON report_schedule(tenant_id);

-- ==========================================
-- EMAIL CAMPAIGN SYSTEM
-- ==========================================

CREATE TABLE IF NOT EXISTS email_template_config (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_type VARCHAR(50) NOT NULL,
    subject_template VARCHAR(500) NOT NULL,
    body_html TEXT NOT NULL,
    body_text TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    is_default_for_type BOOLEAN DEFAULT FALSE,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_tpl_tenant ON email_template_config(tenant_id);

CREATE TABLE IF NOT EXISTS email_campaign (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_id BIGINT REFERENCES email_template_config(id),
    campaign_type VARCHAR(20) NOT NULL,
    recipient_filter_json TEXT,
    attachment_type VARCHAR(20) DEFAULT 'NONE',
    attachment_report_template_id BIGINT,
    statement_month VARCHAR(10),
    schedule_cron VARCHAR(100),
    schedule_timezone VARCHAR(50),
    status VARCHAR(20) DEFAULT 'DRAFT',
    total_recipients INTEGER DEFAULT 0,
    sent_count INTEGER DEFAULT 0,
    failed_count INTEGER DEFAULT 0,
    sent_at TIMESTAMP,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_camp_tenant ON email_campaign(tenant_id);
CREATE INDEX IF NOT EXISTS idx_email_camp_status ON email_campaign(tenant_id, status);

CREATE TABLE IF NOT EXISTS email_campaign_log (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES email_campaign(id),
    tenant_id BIGINT NOT NULL,
    merchant_id BIGINT,
    merchant_name VARCHAR(255),
    recipient_email VARCHAR(255),
    subject_rendered VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    sent_at TIMESTAMP,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_clog_campaign ON email_campaign_log(campaign_id);
CREATE INDEX IF NOT EXISTS idx_email_clog_tenant ON email_campaign_log(tenant_id, sent_at DESC);

-- Default email templates
INSERT INTO email_template_config (tenant_id, name, template_type, subject_template, body_html, is_active, is_default_for_type) VALUES
(1, 'Monthly Statement', 'STATEMENT',
 'Your {{month}} Performance Statement - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:#0f172a;color:#fff;padding:30px;text-align:center}.header h1{margin:0;font-size:24px}.content{padding:40px 30px}.greeting{font-size:18px;color:#1e293b;margin-bottom:20px}.card{background:#f1f5f9;border-radius:8px;padding:20px;margin-bottom:20px;border:1px solid #e2e8f0}.stats{display:flex;gap:20px;margin:20px 0}.stat{flex:1;text-align:center;padding:15px;background:#fff;border-radius:8px;border:1px solid #e2e8f0}.stat-value{font-size:22px;font-weight:700;color:#0f172a}.stat-label{font-size:12px;color:#64748b;text-transform:uppercase}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8;border-top:1px solid #e2e8f0}</style></head><body><div class="container"><div class="header"><h1>{{tenant_name}}</h1></div><div class="content"><div class="greeting">Dear {{contact_name}},</div><p>Your performance statement for <strong>{{month}}</strong> is now available.</p><div class="card"><div style="font-size:14px;color:#64748b;text-transform:uppercase;font-weight:600;margin-bottom:10px">Performance Summary</div><div class="stats"><div class="stat"><div class="stat-value">{{total_count}}</div><div class="stat-label">Transactions</div></div><div class="stat"><div class="stat-value">{{total_volume}}</div><div class="stat-label">Volume</div></div><div class="stat"><div class="stat-value">{{total_msf}}</div><div class="stat-label">MSF Revenue</div></div></div></div><p style="color:#64748b">Please find the detailed PDF report attached to this email.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.<br>This is an automated message.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Welcome Email', 'WELCOME',
 'Welcome to {{tenant_name}} - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:linear-gradient(135deg,#2563eb,#7c3aed);color:#fff;padding:40px;text-align:center}.header h1{margin:0;font-size:28px}.content{padding:40px 30px}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>Welcome!</h1></div><div class="content"><h2>Hello {{contact_name}},</h2><p>Welcome to <strong>{{tenant_name}}</strong>! We are excited to have <strong>{{merchant_name}}</strong> onboard.</p><p>Your Merchant ID is <strong>{{mid}}</strong>.</p><p>You can view your transaction data, performance analytics, and monthly statements through our portal.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Dormancy Alert', 'ALERT',
 'Action Required: {{merchant_name}} - No recent transactions',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden}.header{background:#dc2626;color:#fff;padding:30px;text-align:center}.content{padding:40px 30px}.alert-box{background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:20px;margin:20px 0}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>⚠ Activity Alert</h1></div><div class="content"><p>Dear Team,</p><div class="alert-box"><strong>{{merchant_name}}</strong> (MID: {{mid}}) has not processed transactions in <strong>{{days_since_last_txn}} days</strong>.</div><p>Location: {{city}}<br>Status: {{merchant_status}}<br>Stores: {{store_count}} | Terminals: {{terminal_count}}</p><p>Please follow up to ensure the merchant is still active.</p></div><div class="footer">&copy; 2026 {{tenant_name}}</div></div></body></html>',
 TRUE, TRUE)
ON CONFLICT DO NOTHING;

-- Menu entries for Email Campaign Hub
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Email Campaigns',  '/admin/email-campaigns',  'MailOpen',  'OPERATIONS', 4)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN') AND m.path = '/admin/email-campaigns'
ON CONFLICT DO NOTHING;

-- Menu entry for SSO Settings
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('SSO Settings',  '/admin/sso-settings',  'Shield',  'ADMINISTRATION', 5)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN') AND m.path = '/admin/sso-settings'
ON CONFLICT DO NOTHING;

-- Menu entry for Data Migration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Data Migration',  '/admin/data-migration',  'DatabaseZap',  'ADMINISTRATION', 7)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin') AND m.path = '/admin/data-migration'
ON CONFLICT DO NOTHING;

-- Menu entry for Sales Leaderboard
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Sales Leaderboard',  '/sales/leaderboard',  'Trophy',  'SALES', 2)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN', 'BUSINESS') AND m.path = '/sales/leaderboard'
ON CONFLICT DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- PDF Optimization — contact_email + email_queue
-- ═══════════════════════════════════════════════════════════

-- Add contact_email to dim_merchant for merchant report emailing
-- (Using ADD COLUMN IF NOT EXISTS — avoids DO $ blocks which break Spring ScriptUtils)
ALTER TABLE dim_merchant ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255);

-- Email queue table for async email processing
CREATE TABLE IF NOT EXISTS email_queue (
    id              BIGSERIAL PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(500),
    body            TEXT,
    attachment_path VARCHAR(1000),
    status          VARCHAR(20) DEFAULT 'PENDING',
    error_message   TEXT,
    retry_count     INT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    sent_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_email_queue_status ON email_queue(status);
CREATE INDEX IF NOT EXISTS idx_email_queue_pending ON email_queue(status, created_at) WHERE status = 'PENDING';

-- Refresh Token tracking (#14: rotation + revocation)
CREATE TABLE IF NOT EXISTS refresh_token (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    issued_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by     VARCHAR(128),
    user_agent      VARCHAR(500),
    ip_address      VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_username ON refresh_token(username);
CREATE INDEX IF NOT EXISTS idx_refresh_token_hash ON refresh_token(token_hash);

-- SSO State Tokens (#7: persist across restart)
CREATE TABLE IF NOT EXISTS sso_state_token (
    state_token     VARCHAR(100) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_sso_state_expires ON sso_state_token(expires_at);

-- ═══════════════════════════════════════════════════════════
-- Menu entries for Session 17 new admin screens
-- ═══════════════════════════════════════════════════════════

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Security Settings',  '/admin/security-settings',  'ShieldCheck',  'ADMINISTRATION', 6),
('Alerts & Notifications',  '/admin/alerts',  'BellRing',  'OPERATIONS', 5),
('API Management',  '/admin/api-management',  'Code',  'ADMINISTRATION', 8)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'SUPER_ADMIN', 'ADMIN')
  AND m.path IN ('/admin/security-settings', '/admin/alerts', '/admin/api-management')
ON CONFLICT DO NOTHING;



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

-- ==========================================
-- 2. COMPLETE MENU REGISTRY
-- Every menu here maps 1:1 to a route in App.jsx
-- No hardcoded menus in frontend — this is the single source of truth
-- ==========================================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
-- EXECUTIVE
('Dashboard',                '/dashboard',                        'LayoutDashboard', 'EXECUTIVE',      1),
('Executive Dashboard',      '/business/executive-dashboard-v2',  'Presentation',    'EXECUTIVE',      2),

-- MERCHANT MGT
('Merchant Universe',        '/merchants',                        'Store',           'MERCHANT MGT',   1),
('Transactions',             '/transactions',                     'List',            'MERCHANT MGT',   2),
('Merchant Summary',         '/merchant-summary',                 'Table',           'MERCHANT MGT',   3),
('Merchant Insight Hub',     '/merchant/insight-hub',             'PieChart',        'MERCHANT MGT',   4),
('Transaction Trends',       '/trends/hub',                       'Activity',        'MERCHANT MGT',   5),

-- BUSINESS
('Business Dashboard',       '/business/dashboard',               'LayoutGrid',      'BUSINESS',       0),
('Volume & Revenue',         '/business/volume-revenue',          'BarChart3',       'BUSINESS',       1),
('Merchant Financial',       '/business/merchant-financial',      'DollarSign',      'BUSINESS',       2),
('Performance Trends',       '/business/performance',             'TrendingUp',      'BUSINESS',       3),
('Debit & Prepaid Metrics',  '/business/debit-prepaid',           'CreditCard',      'BUSINESS',       4),
('Attrition Report',         '/business/attrition',               'TrendingDown',    'BUSINESS',       5),
('Zero Transaction Report',  '/business/zero-transaction',        'AlertTriangle',   'BUSINESS',       6),
('Merchant Growth Heatmap',  '/business/heatmap',                 'Grid',            'BUSINESS',       7),
('Daily Merchant Dashboard', '/business/daily-dashboard',         'Calendar',        'BUSINESS',       8),
('Merchant Analytics',       '/business/merchant-analytics',      'BarChart2',       'BUSINESS',       9),
('Merchant Comparison',      '/business/comparison',              'Scale',           'BUSINESS',      10),
('Report Manager',           '/business/report-manager',          'FileText',        'BUSINESS',      11),
('Opportunity Intelligence', '/business/opportunity',             'Target',          'BUSINESS',      12),
('Group Reports',            '/business/groups',                  'FolderKanban',    'BUSINESS',      13),
('Data Explorer',            '/explorer',                         'Compass',         'BUSINESS',      14),
('AI Assistant',             '/ai-assistant',                     'BrainCircuit',    'BUSINESS',      15),

-- SALES
('Sales Team Management',    '/sales/team-management',            'Users',           'SALES',          1),

-- FINANCE
('Finance Dashboard',        '/finance/dashboard',                'PieChart',        'FINANCE',        1),
('Finance Summary',          '/finance/summary',                  'BookOpen',        'FINANCE',        2),
('Finance Lists',            '/finance/lists',                    'ClipboardList',   'FINANCE',        3),

-- OPERATIONS
('Upload Files',             '/upload',                           'Upload',          'OPERATIONS',     1),
('Server File Processor',    '/ops/server-file',                  'HardDrive',       'OPERATIONS',     2),
('Batch Logs',               '/ops/batch-logs',                   'Activity',        'OPERATIONS',     3),
('Email Manager',            '/business/emails',                  'Mail',            'OPERATIONS',     4),

-- ADMINISTRATION
('User Management',          '/users',                            'Users',           'ADMINISTRATION', 1),
('Bank Setup',               '/tenants',                          'Building',        'ADMINISTRATION', 2),
('Group Management',         '/admin/groups',                     'Shield',          'ADMINISTRATION', 3),
('SMTP Settings',            '/admin/smtp-settings',              'Settings',        'ADMINISTRATION', 4),
('Audit Logs',               '/admin/audit-logs',                 'ScrollText',      'ADMINISTRATION', 5),
('Backup & Restore',         '/admin/backups',                    'Database',        'ADMINISTRATION', 6)
ON CONFLICT (path) DO NOTHING;

-- ==========================================
-- 3. GROUP → MENU ASSIGNMENTS (RBAC)
-- ==========================================

-- Super Admin: ALL menus
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
ON CONFLICT DO NOTHING;

-- Bank Admin: Everything except sensitive admin pages
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Bank Admin'
  AND m.path NOT IN ('/admin/groups', '/admin/smtp-settings', '/admin/audit-logs', '/admin/backups')
ON CONFLICT DO NOTHING;

-- Business User: Executive + Business + Merchant Mgt + Sales
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Business User'
  AND m.category IN ('EXECUTIVE', 'BUSINESS', 'MERCHANT MGT', 'SALES')
ON CONFLICT DO NOTHING;

-- Finance User: Dashboard + Finance + Merchant Mgt
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Finance User'
  AND (m.category IN ('FINANCE', 'MERCHANT MGT') OR m.path = '/dashboard')
ON CONFLICT DO NOTHING;

-- Ops User: Dashboard + Operations + Backup
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Ops User'
  AND (m.category = 'OPERATIONS' OR m.path IN ('/dashboard', '/admin/backups'))
ON CONFLICT DO NOTHING;

-- 1.2 Tenant (The Core Institution/Bank Unit)
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
    last_login TIMESTAMP,
    -- Password Management
    must_change_password BOOLEAN DEFAULT FALSE,
    password_changed_at TIMESTAMP,
    -- Account Lockout
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMP,
    last_failed_login TIMESTAMP
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
    username VARCHAR(50),
    action_type VARCHAR(50), -- LOGIN, EXPORT, BATCH_RUN
    details TEXT,
    ip_address VARCHAR(45),
    event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    http_method VARCHAR(10),
    endpoint VARCHAR(255),
    status_code INT,
    user_agent VARCHAR(500),
    category VARCHAR(50),
    entity_type VARCHAR(50),
    entity_id VARCHAR(100),
    duration_ms BIGINT
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
-- #21: Use MONTHLY partitions for fact_transaction (aligned with PartitionMaintenanceService)
-- Yearly partitions removed to avoid overlap with monthly partitions.
-- PartitionMaintenanceService.ensurePartitionsForYear() creates monthly partitions
-- (e.g. fact_transaction_y2025m01 ... fact_transaction_y2025m12) at application startup.
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
    total_msf DECIMAL(21, 4),
    total_interchange DECIMAL(19, 2),
    total_scheme_fee DECIMAL(19, 2), 
    total_margin DECIMAL(19, 2),
    
    total_debit_prepaid_volume DECIMAL(19, 2) DEFAULT 0,
    total_credit_volume DECIMAL(19, 2) DEFAULT 0,
    sales_user_id VARCHAR(50),
    
    unique_customer_count BIGINT DEFAULT 0,
    top_spending_customer_id VARCHAR(50),
    top_spending_amount DECIMAL(19, 2),
    
    -- Base Currency Volume (Store Base Currency Amount for merchant-facing PDF)
    total_base_volume DECIMAL(19, 2) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    total_msf DECIMAL(21, 4),
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    dom_debit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_debit_optin DECIMAL(19, 2) DEFAULT 0, -- Opt-in Volume (DCC)

    -- Domestic Credit
    dom_credit_cnt BIGINT DEFAULT 0,
    dom_credit_vol DECIMAL(19, 2) DEFAULT 0,
    dom_credit_msf DECIMAL(21, 4) DEFAULT 0,
    dom_credit_optin DECIMAL(19, 2) DEFAULT 0,

    -- International
    int_cnt BIGINT DEFAULT 0,
    int_vol DECIMAL(19, 2) DEFAULT 0,
    int_msf DECIMAL(21, 4) DEFAULT 0,
    int_optin DECIMAL(19, 2) DEFAULT 0,

    -- Totals
    total_vol DECIMAL(19, 2) DEFAULT 0,
    total_msf DECIMAL(21, 4) DEFAULT 0,

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
    total_msf DECIMAL(21, 4) DEFAULT 0,
    
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
    total_msf DECIMAL(21, 4) DEFAULT 0,
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
    volatility_index DECIMAL(19, 4),
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
INSERT INTO users (username, password_hash, email, role, is_active, must_change_password) VALUES 
('admin', '{noop}password', 'admin@acquira.com', 'ROLE_SUPER_ADMIN', true, false)
ON CONFLICT (username) DO UPDATE 
SET password_hash = EXCLUDED.password_hash, must_change_password = FALSE, role = 'ROLE_SUPER_ADMIN';

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
-- (V2026_08_01_01) idx_fact_txn_card removed: no report query reads fact by card_number (card lookups use sum_monthly_card); at 300M+ rows/yr it was the most expensive index to maintain. Recreating it here would undo that migration's DROP.

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
-- SALES TEAM MAPPING & ASSIGNMENT
-- ==========================================
CREATE TABLE IF NOT EXISTS sales_team_mapping (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    team_lead_name  VARCHAR(100) NOT NULL,
    team_lead_email VARCHAR(150) NOT NULL,
    is_default      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_team_tenant_email UNIQUE (tenant_id, team_lead_email)
);
CREATE INDEX IF NOT EXISTS idx_sales_team_tenant ON sales_team_mapping(tenant_id);

CREATE TABLE IF NOT EXISTS sales_user_assignment (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    sales_user_id   VARCHAR(100) NOT NULL,
    team_lead_id    BIGINT NOT NULL REFERENCES sales_team_mapping(id),
    assigned_at     TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_sales_user_tenant UNIQUE (tenant_id, sales_user_id)
);
CREATE INDEX IF NOT EXISTS idx_sales_assign_tenant ON sales_user_assignment(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_assign_lead ON sales_user_assignment(team_lead_id);

-- Default Team Lead (auto-assign unmapped sales users to this lead)
INSERT INTO sales_team_mapping (tenant_id, team_lead_name, team_lead_email, is_default)
VALUES (1, 'Default Team Lead', 'default-lead@acquira.com', true)
ON CONFLICT (tenant_id, team_lead_email) DO UPDATE SET is_default = true;

-- ==========================================
-- PASSWORD HISTORY
-- ==========================================
CREATE TABLE IF NOT EXISTS password_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_password_history_user ON password_history(user_id);

-- ==========================================
-- PASSWORD RESET TOKENS
-- ==========================================
CREATE TABLE IF NOT EXISTS password_reset_token (
    token_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_reset_token ON password_reset_token(token);

-- ==========================================
-- ALTER existing users table (for upgrades)
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts INT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_failed_login TIMESTAMP;

-- ==========================================
-- SSO & ACCESS REQUEST SUPPORT
-- ==========================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_provider VARCHAR(20);       -- 'MICROSOFT', NULL
ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_id VARCHAR(255);            -- Azure AD Object ID
ALTER TABLE users ADD COLUMN IF NOT EXISTS approval_status VARCHAR(20) DEFAULT 'APPROVED';
    -- APPROVED (normal users), PENDING (SSO requests), REJECTED
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR(150);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS role_in_tenant VARCHAR(50);
ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS is_default_tenant BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS access_request (
    request_id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(150),
    sso_provider VARCHAR(20),
    sso_id VARCHAR(255),
    requested_tenant_id INT REFERENCES tenant(tenant_id),
    message TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    reviewed_by BIGINT REFERENCES users(user_id),
    reviewed_at TIMESTAMP,
    review_notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- SSO Configuration per tenant (admin toggle)
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_enabled', 'false', 'BOOLEAN'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_provider', 'MICROSOFT', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_tenant_id', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'sso_client_secret', '', 'STRING'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Ensure existing users are NOT forced to change password
UPDATE users SET must_change_password = FALSE WHERE must_change_password IS NULL;

-- ==========================================
-- PASSWORD & LOCKOUT CONFIG (per-tenant)
-- ==========================================
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_history_count', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_min_length', '8', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'max_failed_logins', '5', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'lockout_duration_minutes', '15', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'password_reset_token_expiry_hours', '1', 'NUMBER'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;

-- Seed admin password into history
INSERT INTO password_history (user_id, password_hash)
SELECT user_id, password_hash FROM users WHERE username = 'admin'
ON CONFLICT DO NOTHING;
-- AI Assistant menu: managed in consolidated menu block above

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
-- Join key for the stagingToFact store_id/terminal_id fix-up UPDATEs, which match
-- fact rows to staging on (payment_date, arn). Without this the fix-ups hash the
-- whole staging table on every upload. See V2026_08_03_01.
CREATE INDEX IF NOT EXISTS idx_stg_txn_arn_date ON stg_trnx_raw (tenant_id, arn, payment_date);

-- Data Explorer menu: managed in consolidated menu block above
-- (saved_filter already created above)

-- ==========================================
-- DATA INTEGRATION HUB
-- ==========================================

-- External DB connections (per tenant)
CREATE TABLE IF NOT EXISTS integration_connection (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    db_type VARCHAR(50) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    db_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    encrypted_password TEXT NOT NULL,
    timeout_seconds INTEGER DEFAULT 30,
    max_retries INTEGER DEFAULT 3,
    is_active BOOLEAN DEFAULT TRUE,
    last_test_at TIMESTAMP,
    last_test_status VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_conn_tenant ON integration_connection(tenant_id);

-- Report configs (per tenant, per type: MERCHANT or TRANSACTION)
CREATE TABLE IF NOT EXISTS integration_report (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connection_id BIGINT REFERENCES integration_connection(id),
    name VARCHAR(255) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    sql_text TEXT NOT NULL,
    column_mapping TEXT,
    description TEXT,
    param_schema TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    approved_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_report_tenant ON integration_report(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_report_type ON integration_report(tenant_id, report_type);

-- Schedule configs (independent per report per tenant)
CREATE TABLE IF NOT EXISTS integration_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_sched_tenant ON integration_schedule(tenant_id);
CREATE INDEX IF NOT EXISTS idx_intg_sched_enabled ON integration_schedule(is_enabled);

-- Run history with retry tracking
CREATE TABLE IF NOT EXISTS integration_run_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    report_id BIGINT REFERENCES integration_report(id),
    schedule_id BIGINT REFERENCES integration_schedule(id),
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_number INTEGER DEFAULT 1,
    max_retries INTEGER DEFAULT 3,
    rows_fetched INTEGER DEFAULT 0,
    rows_processed INTEGER DEFAULT 0,
    rows_failed INTEGER DEFAULT 0,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    error_message TEXT,
    date_range_from DATE,
    date_range_to DATE,
    duration_ms BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_intg_run_tenant ON integration_run_log(tenant_id, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_intg_run_status ON integration_run_log(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_intg_run_report ON integration_run_log(report_id);

-- Menu entries for Data Integration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Integration Hub',       '/admin/integration',              'Cable',           'DATA INTEGRATION', 1),
('DB Connections',        '/admin/integration/connections',   'Database',        'DATA INTEGRATION', 2),
('Report Configs',        '/admin/integration/reports',       'FileCode',        'DATA INTEGRATION', 3),
('Schedules',             '/admin/integration/schedules',     'Clock',           'DATA INTEGRATION', 4),
('Run History',           '/admin/integration/runs',          'ScrollText',      'DATA INTEGRATION', 5)
ON CONFLICT (path) DO NOTHING;

-- Grant integration menus to SUPER_ADMIN and ADMIN groups
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'SUPER_ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'ADMIN' AND m.category = 'DATA INTEGRATION'
ON CONFLICT DO NOTHING;

-- ==========================================
-- REPORT BUILDER & TEMPLATES
-- ==========================================

CREATE TABLE IF NOT EXISTS report_template (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    config_json TEXT NOT NULL,
    is_shared BOOLEAN DEFAULT FALSE,
    last_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_tpl_tenant ON report_template(tenant_id, user_id);

CREATE TABLE IF NOT EXISTS report_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    template_id BIGINT REFERENCES report_template(id),
    cron_expression VARCHAR(100) NOT NULL,
    frequency_label VARCHAR(50),
    timezone VARCHAR(50) DEFAULT 'UTC',
    delivery_method VARCHAR(20) DEFAULT 'EMAIL',
    recipient_emails TEXT,
    export_format VARCHAR(10) DEFAULT 'EXCEL',
    is_enabled BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP,
    next_run_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_sched_tenant ON report_schedule(tenant_id);

-- ==========================================
-- EMAIL CAMPAIGN SYSTEM
-- ==========================================

CREATE TABLE IF NOT EXISTS email_template_config (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_type VARCHAR(50) NOT NULL,
    subject_template VARCHAR(500) NOT NULL,
    body_html TEXT NOT NULL,
    body_text TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    is_default_for_type BOOLEAN DEFAULT FALSE,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_tpl_tenant ON email_template_config(tenant_id);

CREATE TABLE IF NOT EXISTS email_campaign (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_id BIGINT REFERENCES email_template_config(id),
    campaign_type VARCHAR(20) NOT NULL,
    recipient_filter_json TEXT,
    attachment_type VARCHAR(20) DEFAULT 'NONE',
    attachment_report_template_id BIGINT,
    statement_month VARCHAR(10),
    schedule_cron VARCHAR(100),
    schedule_timezone VARCHAR(50),
    status VARCHAR(20) DEFAULT 'DRAFT',
    total_recipients INTEGER DEFAULT 0,
    sent_count INTEGER DEFAULT 0,
    failed_count INTEGER DEFAULT 0,
    sent_at TIMESTAMP,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_camp_tenant ON email_campaign(tenant_id);
CREATE INDEX IF NOT EXISTS idx_email_camp_status ON email_campaign(tenant_id, status);

CREATE TABLE IF NOT EXISTS email_campaign_log (
    id BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT NOT NULL REFERENCES email_campaign(id),
    tenant_id BIGINT NOT NULL,
    merchant_id BIGINT,
    merchant_name VARCHAR(255),
    recipient_email VARCHAR(255),
    subject_rendered VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    sent_at TIMESTAMP,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_clog_campaign ON email_campaign_log(campaign_id);
CREATE INDEX IF NOT EXISTS idx_email_clog_tenant ON email_campaign_log(tenant_id, sent_at DESC);

-- Default email templates
INSERT INTO email_template_config (tenant_id, name, template_type, subject_template, body_html, is_active, is_default_for_type) VALUES
(1, 'Monthly Statement', 'STATEMENT',
 'Your {{month}} Performance Statement - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:#0f172a;color:#fff;padding:30px;text-align:center}.header h1{margin:0;font-size:24px}.content{padding:40px 30px}.greeting{font-size:18px;color:#1e293b;margin-bottom:20px}.card{background:#f1f5f9;border-radius:8px;padding:20px;margin-bottom:20px;border:1px solid #e2e8f0}.stats{display:flex;gap:20px;margin:20px 0}.stat{flex:1;text-align:center;padding:15px;background:#fff;border-radius:8px;border:1px solid #e2e8f0}.stat-value{font-size:22px;font-weight:700;color:#0f172a}.stat-label{font-size:12px;color:#64748b;text-transform:uppercase}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8;border-top:1px solid #e2e8f0}</style></head><body><div class="container"><div class="header"><h1>{{tenant_name}}</h1></div><div class="content"><div class="greeting">Dear {{contact_name}},</div><p>Your performance statement for <strong>{{month}}</strong> is now available.</p><div class="card"><div style="font-size:14px;color:#64748b;text-transform:uppercase;font-weight:600;margin-bottom:10px">Performance Summary</div><div class="stats"><div class="stat"><div class="stat-value">{{total_count}}</div><div class="stat-label">Transactions</div></div><div class="stat"><div class="stat-value">{{total_volume}}</div><div class="stat-label">Volume</div></div><div class="stat"><div class="stat-value">{{total_msf}}</div><div class="stat-label">MSF Revenue</div></div></div></div><p style="color:#64748b">Please find the detailed PDF report attached to this email.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.<br>This is an automated message.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Welcome Email', 'WELCOME',
 'Welcome to {{tenant_name}} - {{merchant_name}}',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 4px 6px rgba(0,0,0,.05)}.header{background:linear-gradient(135deg,#2563eb,#7c3aed);color:#fff;padding:40px;text-align:center}.header h1{margin:0;font-size:28px}.content{padding:40px 30px}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>Welcome!</h1></div><div class="content"><h2>Hello {{contact_name}},</h2><p>Welcome to <strong>{{tenant_name}}</strong>! We are excited to have <strong>{{merchant_name}}</strong> onboard.</p><p>Your Merchant ID is <strong>{{mid}}</strong>.</p><p>You can view your transaction data, performance analytics, and monthly statements through our portal.</p></div><div class="footer">&copy; 2026 {{tenant_name}}. All rights reserved.</div></div></body></html>',
 TRUE, TRUE),
(1, 'Dormancy Alert', 'ALERT',
 'Action Required: {{merchant_name}} - No recent transactions',
 '<!DOCTYPE html><html><head><style>body{font-family:Helvetica,Arial,sans-serif;line-height:1.6;color:#333;background:#f9fafb;margin:0;padding:0}.container{max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden}.header{background:#dc2626;color:#fff;padding:30px;text-align:center}.content{padding:40px 30px}.alert-box{background:#fef2f2;border:1px solid #fecaca;border-radius:8px;padding:20px;margin:20px 0}.footer{background:#f8fafc;padding:20px;text-align:center;font-size:12px;color:#94a3b8}</style></head><body><div class="container"><div class="header"><h1>⚠ Activity Alert</h1></div><div class="content"><p>Dear Team,</p><div class="alert-box"><strong>{{merchant_name}}</strong> (MID: {{mid}}) has not processed transactions in <strong>{{days_since_last_txn}} days</strong>.</div><p>Location: {{city}}<br>Status: {{merchant_status}}<br>Stores: {{store_count}} | Terminals: {{terminal_count}}</p><p>Please follow up to ensure the merchant is still active.</p></div><div class="footer">&copy; 2026 {{tenant_name}}</div></div></body></html>',
 TRUE, TRUE)
ON CONFLICT DO NOTHING;

-- Menu entries for Email Campaign Hub
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Email Campaigns',  '/admin/email-campaigns',  'MailOpen',  'OPERATIONS', 4)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN') AND m.path = '/admin/email-campaigns'
ON CONFLICT DO NOTHING;

-- Menu entry for SSO Settings
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('SSO Settings',  '/admin/sso-settings',  'Shield',  'ADMINISTRATION', 5)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN') AND m.path = '/admin/sso-settings'
ON CONFLICT DO NOTHING;

-- Menu entry for Data Migration
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Data Migration',  '/admin/data-migration',  'DatabaseZap',  'ADMINISTRATION', 7)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin') AND m.path = '/admin/data-migration'
ON CONFLICT DO NOTHING;

-- Menu entry for Sales Leaderboard
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Sales Leaderboard',  '/sales/leaderboard',  'Trophy',  'SALES', 2)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN', 'BUSINESS') AND m.path = '/sales/leaderboard'
ON CONFLICT DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- PDF Optimization — contact_email + email_queue
-- ═══════════════════════════════════════════════════════════

-- Add contact_email to dim_merchant for merchant report emailing
-- (Using ADD COLUMN IF NOT EXISTS — avoids DO $ blocks which break Spring ScriptUtils)
ALTER TABLE dim_merchant ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255);

-- Email queue table for async email processing
CREATE TABLE IF NOT EXISTS email_queue (
    id              BIGSERIAL PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(500),
    body            TEXT,
    attachment_path VARCHAR(1000),
    status          VARCHAR(20) DEFAULT 'PENDING',
    error_message   TEXT,
    retry_count     INT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    sent_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_email_queue_status ON email_queue(status);
CREATE INDEX IF NOT EXISTS idx_email_queue_pending ON email_queue(status, created_at) WHERE status = 'PENDING';

-- Refresh Token tracking (#14: rotation + revocation)
CREATE TABLE IF NOT EXISTS refresh_token (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    issued_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by     VARCHAR(128),
    user_agent      VARCHAR(500),
    ip_address      VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_username ON refresh_token(username);
CREATE INDEX IF NOT EXISTS idx_refresh_token_hash ON refresh_token(token_hash);

-- SSO State Tokens (#7: persist across restart)
CREATE TABLE IF NOT EXISTS sso_state_token (
    state_token     VARCHAR(100) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_sso_state_expires ON sso_state_token(expires_at);

-- ═══════════════════════════════════════════════════════════
-- Menu entries for Session 17 new admin screens
-- ═══════════════════════════════════════════════════════════

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Security Settings',  '/admin/security-settings',  'ShieldCheck',  'ADMINISTRATION', 6),
('Alerts & Notifications',  '/admin/alerts',  'BellRing',  'OPERATIONS', 5),
('API Management',  '/admin/api-management',  'Code',  'ADMINISTRATION', 8)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'SUPER_ADMIN', 'ADMIN')
  AND m.path IN ('/admin/security-settings', '/admin/alerts', '/admin/api-management')
ON CONFLICT DO NOTHING;

INSERT INTO ref_country (country_code, country_name, currency_code, currency_name, currency_symbol, phone_code, iso_numeric, decimal_notation_value) VALUES
('AF', 'AFGHANISTAN', 'AFN', 'Afghani', 'AFN', '93', '971', 100),
('AL', 'ALBANIA', 'ALL', 'Lek', 'Lek', '355', '008', 100),
('DZ', 'ALGERIA', 'DZD', 'Dinar', 'DZD', '213', '012', 100),
('AO', 'ANGOLA', 'AOA', 'Kwanza', 'Kz', '244', '973', 100),
('AG', 'ANTIGUA AND BARBUDA', 'XCD', 'Dollar', 'XCD', '1268', '951', 100),
('AW', 'ARUBA', 'AWG', 'Guilder', 'ƒ', '297', '533', 100),
('AU', 'AUSTRALIA', 'AUD', 'Dollar', 'AUD', '61', '036', 100),
('AZ', 'AZERBAIJAN', 'AZN', 'Manat', 'AZN', '994', '944', 100),
('BS', 'BAHAMAS', 'BSD', 'Dollar', 'BSD', '1242', '044', 100),
('BH', 'BAHRAIN', 'BHD', 'Dinar', 'BHD', '973', '048', 1000),
('BD', 'BANGLADESH', 'BDT', 'Taka', 'BDT', '880', '050', 100),
('BB', 'BARBADOS', 'BBD', 'Dollar', 'BBD', '1246', '052', 100),
('BY', 'BELARUS', 'BYR', 'Ruble', 'p.', '375', '933', 100),
('BZ', 'BELIZE', 'BZD', 'Dollar', 'BZD', '501', '084', 100),
('BM', 'BERMUDA', 'BMD', 'Dollar', 'BMD', '1441', '060', 100),
('BT', 'BHUTAN', 'BTN', 'Ngultrum', 'BTN', '975', '064', 100),
('BO', 'BOLIVIA', 'BOB', 'Boliviano', 'BOB', '591', '068', 100),
('BA', 'BOSNIA AND HERZEGOVINA', 'BAM', 'Marka', 'KM', '387', '977', 100),
('BW', 'BOTSWANA', 'BWP', 'Pula', 'P', '267', '072', 100),
('BN', 'BRUNEI DARUSSALAM', 'BND', 'Dollar', 'BND', '673', '096', 100),
('BR', 'BRAZIL', 'BRL', 'Real', 'BRL', '55', '986', 100),
('BI', 'BURUNDI', 'BIF', 'Franc', 'BIF', '257', '108', 100),
('KH', 'CAMBODIA', 'KHR', 'Riels', 'KHR', '855', '116', 100),
('CA', 'CANADA', 'CAD', 'Dollar', 'CAD', '1', '124', 100),
('CV', 'CAPE VERDE', 'CVE', 'Escudo', 'CVE', '238', '132', 100),
('KY', 'CAYMAN ISLANDS', 'KYD', 'Dollar', 'KYD', '1345', '136', 100),
('CF', 'CENTRAL AFRICAN REPUBLIC', 'XAF', 'Franc', 'FCF', '236', '950', 100),
('CL', 'CHILE', 'CLP', 'Peso', 'CLP', '56', '152', 100),
('CN', 'CHINA', 'CNY', 'Yuan Renminbi', '¥', '86', '156', 100),
('CC', 'COCOS (KEELING) ISLANDS', 'AUD', 'Dollar', 'AUD', '672', '166', 100),
('CO', 'COLOMBIA', 'COP', 'Peso', 'COP', '57', '170', 100),
('KM', 'COMOROS', 'KMF', 'Franc', 'KMF', '269', '174', 100),
('CK', 'COOK ISLANDS', 'NZD', 'Dollar', 'NZD', '682', '184', 100),
('CR', 'COSTA RICA', 'CRC', 'Colon', '¢', '506', '188', 100),
('HR', 'CROATIA', 'HRK', 'Kuna', 'kn', '385', '191', 100),
('CU', 'CUBA', 'CUP', 'Peso', 'CUP', '53', '192', 100),
('CY', 'CYPRUS', 'CYP', 'Pound', 'CYP', '357', '196', 100),
('CZ', 'CZECH REPUBLIC', 'CZK', 'Koruna', 'Kc', '420', '203', 100),
('CD', 'CONGO, THE DEMOCRATIC REPUBLIC OF THE', 'CDF', 'Franc', 'CDF', '242', '180', 100),
('DK', 'DENMARK', 'DKK', 'Krone', 'kr', '45', '208', 100),
('DJ', 'DJIBOUTI', 'DJF', 'Franc', 'DJF', '253', '262', 100),
('DM', 'DOMINICA', 'XCD', 'Dollar', 'XCD', '1767', '212', 100),
('DO', 'DOMINICAN REPUBLIC', 'DOP', 'Peso', 'DOP', '1809', '214', 100),
('TL', 'TIMOR-LESTE', 'USD', 'Dollar', 'USD', '670', '626', 100),
('EC', 'ECUADOR', 'USD', 'Dollar', 'USD', '593', '218', 100),
('EG', 'EGYPT', 'EGP', 'Pound', '£', '20', '818', 100),
('SV', 'EL SALVADOR', 'SVC', 'Colone', 'SVC', '503', '222', 100),
('GQ', 'EQUATORIAL GUINEA', 'XAF', 'Franc', 'FCF', '240', '226', 100),
('ER', 'ERITREA', 'ERN', 'Nakfa', 'Nfk', '291', '232', 100),
('EE', 'ESTONIA', 'EEK', 'Kroon', 'kr', '372', '233', 100),
('ET', 'ETHIOPIA', 'ETB', 'Birr', 'ETB', '251', '231', 100),
('FK', 'FALKLAND ISLANDS (MALVINAS)', 'FKP', 'Pound', '£', '500', '238', 100),
('FO', 'FAROE ISLANDS', 'DKK', 'Krone', 'kr', '298', '234', 100),
('FJ', 'FIJI', 'FJD', 'Dollar', 'FJD', '679', '242', 100),
('FI', 'FINLAND', 'EUR', 'Euro', '€', '358', '246', 100),
('FR', 'FRANCE', 'EUR', 'Euro', '€', '33', '250', 100),
('GF', 'FRENCH GUIANA', 'EUR', 'Euro', '€', '594', '254', 100),
('PF', 'FRENCH POLYNESIA', 'XPF', 'Franc', 'XPF', '689', '258', 100),
('TF', 'FRENCH SOUTHERN TERRITORIES', 'EUR', 'Euro', '€', '0', '260', 100),
('GA', 'GABON', 'XAF', 'Franc', 'FCF', '241', '266', 100),
('GM', 'GAMBIA', 'GMD', 'Dalasi', 'D', '220', '270', 100),
('GE', 'GEORGIA', 'GEL', 'Lari', 'GEL', '995', '268', 100),
('DE', 'GERMANY', 'EUR', 'Euro', '€', '49', '276', 100),
('GH', 'GHANA', 'GHC', 'Cedi', '¢', '233', '288', 100),
('GI', 'GIBRALTAR', 'GIP', 'Pound', '£', '350', '292', 100),
('GR', 'GREECE', 'EUR', 'Euro', '€', '30', '300', 100),
('GL', 'GREENLAND', 'DKK', 'Krone', 'kr', '299', '304', 100),
('GD', 'GRENADA', 'XCD', 'Dollar', 'XCD', '1473', '308', 100),
('GP', 'GUADELOUPE', 'EUR', 'Euro', '€', '590', '312', 100),
('GU', 'GUAM', 'USD', 'Dollar', 'USD', '1671', '316', 100),
('GT', 'GUATEMALA', 'GTQ', 'Quetzal', 'Q', '502', '320', 100),
('GN', 'GUINEA', 'GNF', 'Franc', 'GNF', '224', '324', 100),
('GW', 'GUINEA-BISSAU', 'XOF', 'Franc', 'XOF', '245', '624', 100),
('GY', 'GUYANA', 'GYD', 'Dollar', 'GYD', '592', '328', 100),
('HT', 'HAITI', 'HTG', 'Gourde', 'G', '509', '332', 100),
('HM', 'HEARD ISLAND AND MCDONALD ISLANDS', 'AUD', 'Dollar', 'AUD', '0', '334', 100),
('HN', 'HONDURAS', 'HNL', 'Lempira', 'L', '504', '340', 100),
('HK', 'HONG KONG', 'HKD', 'Dollar', 'HKD', '852', '344', 100),
('HU', 'HUNGARY', 'HUF', 'Forint', 'Ft', '36', '348', 100),
('IS', 'ICELAND', 'ISK', 'Krona', 'kr', '354', '352', 100),
('IN', 'INDIA', 'INR', 'Rupee', 'INR', '91', '356', 100),
('ID', 'INDONESIA', 'IDR', 'Rupiah', 'Rp', '62', '360', 100),
('IR', 'IRAN, ISLAMIC REPUBLIC OF', 'IRR', 'Rial', 'IRR', '98', '364', 100),
('IQ', 'IRAQ', 'IQD', 'Dinar', 'IQD', '964', '368', 1000),
('IE', 'IRELAND', 'EUR', 'Euro', '€', '353', '372', 100),
('IL', 'ISRAEL', 'ILS', 'Shekel', 'ILS', '972', '376', 100),
('IT', 'ITALY', 'EUR', 'Euro', '€', '39', '380', 100),
('CI', 'COTE D''IVOIRE', 'XOF', 'Franc', 'XOF', '225', '384', 100),
('JM', 'JAMAICA', 'JMD', 'Dollar', 'JMD', '1876', '388', 100),
('JP', 'JAPAN', 'JPY', 'Yen', '¥', '81', '392', 10),
('JO', 'JORDAN', 'JOD', 'Dinar', 'JOD', '962', '400', 1000),
('KZ', 'KAZAKHSTAN', 'KZT', 'Tenge', 'KZT', '7', '398', 100),
('KE', 'KENYA', 'KES', 'Shilling', 'KES', '254', '404', 100),
('KI', 'KIRIBATI', 'AUD', 'Dollar', 'AUD', '686', '296', 100),
('KW', 'KUWAIT', 'KWD', 'Dinar', 'KWD', '965', '414', 1000),
('KG', 'KYRGYZSTAN', 'KGS', 'Som', 'KGS', '996', '417', 100),
('LA', 'LAO PEOPLE''S DEMOCRATIC REPUBLIC', 'LAK', 'Kip', 'LAK', '856', '418', 100),
('LV', 'LATVIA', 'LVL', 'Lat', 'Ls', '371', '428', 100),
('LB', 'LEBANON', 'LBP', 'Pound', '£', '961', '422', 100),
('LS', 'LESOTHO', 'LSL', 'Loti', 'L', '266', '426', 100),
('LR', 'LIBERIA', 'LRD', 'Dollar', 'LRD', '231', '430', 100),
('LY', 'LIBYAN ARAB JAMAHIRIYA', 'LYD', 'Dinar', 'LYD', '218', '434', 1000),
('LI', 'LIECHTENSTEIN', 'CHF', 'Franc', 'CHF', '423', '438', 100),
('LT', 'LITHUANIA', 'LTL', 'Litas', 'Lt', '370', '440', 100),
('LU', 'LUXEMBOURG', 'EUR', 'Euro', '€', '352', '442', 100),
('MO', 'MACAO', 'MOP', 'Pataca', 'MOP', '853', '446', 100),
('MK', 'MACEDONIA, THE FORMER YUGOSLAV REPUBLIC OF', 'MKD', 'Denar', 'MKD', '389', '807', 100),
('MG', 'MADAGASCAR', 'MGA', 'Ariary', 'MGA', '261', '450', 100),
('MW', 'MALAWI', 'MWK', 'Kwacha', 'MK', '265', '454', 100),
('MY', 'MALAYSIA', 'MYR', 'Ringgit', 'RM', '60', '458', 100),
('MV', 'MALDIVES', 'MVR', 'Rufiyaa', 'Rf', '960', '462', 100),
('ML', 'MALI', 'XOF', 'Franc', 'XOF', '223', '466', 100),
('MT', 'MALTA', 'MTL', 'Lira', 'MTL', '356', '470', 100),
('MH', 'MARSHALL ISLANDS', 'USD', 'Dollar', 'USD', '692', '584', 100),
('MQ', 'MARTINIQUE', 'EUR', 'Euro', '€', '596', '474', 100),
('MR', 'MAURITANIA', 'MRO', 'Ouguiya', 'UM', '222', '478', 100),
('MU', 'MAURITIUS', 'MUR', 'Rupee', 'MUR', '230', '480', 100),
('YT', 'MAYOTTE', 'EUR', 'Euro', '€', '269', '175', 100),
('MX', 'MEXICO', 'MXN', 'Peso', 'MXN', '52', '484', 100),
('FM', 'MICRONESIA, FEDERATED STATES OF', 'USD', 'Dollar', 'USD', '691', '583', 100),
('MD', 'MOLDOVA, REPUBLIC OF', 'MDL', 'Leu', 'MDL', '373', '498', 100),
('MC', 'MONACO', 'EUR', 'Euro', '€', '377', '492', 100),
('MN', 'MONGOLIA', 'MNT', 'Tugrik', 'MNT', '976', '496', 100),
('MS', 'MONTSERRAT', 'XCD', 'Dollar', 'XCD', '1664', '500', 100),
('MA', 'MOROCCO', 'MAD', 'Dirham', 'MAD', '212', '504', 100),
('MZ', 'MOZAMBIQUE', 'MZN', 'Meticail', 'MT', '258', '508', 100),
('MM', 'MYANMAR', 'MMK', 'Kyat', 'K', '95', '104', 100),
('NA', 'NAMIBIA', 'NAD', 'Dollar', 'NAD', '264', '516', 100),
('NR', 'NAURU', 'AUD', 'Dollar', 'AUD', '674', '520', 100),
('NP', 'NEPAL', 'NPR', 'Rupee', 'NPR', '977', '524', 100),
('NL', 'NETHERLANDS', 'EUR', 'Euro', '€', '31', '528', 100),
('AN', 'NETHERLANDS ANTILLES', 'ANG', 'Guilder', 'ƒ', '599', '530', 100),
('NC', 'NEW CALEDONIA', 'XPF', 'Franc', 'XPF', '687', '540', 100),
('NZ', 'NEW ZEALAND', 'NZD', 'Dollar', 'NZD', '64', '554', 100),
('NI', 'NICARAGUA', 'NIO', 'Cordoba', 'NIO', '505', '558', 100),
('NE', 'NIGER', 'XOF', 'Franc', 'XOF', '227', '562', 100),
('NG', 'NIGERIA', 'NGN', 'Naira', 'NGN', '234', '566', 100),
('NU', 'NIUE', 'NZD', 'Dollar', 'NZD', '683', '570', 100),
('NF', 'NORFOLK ISLAND', 'AUD', 'Dollar', 'AUD', '672', '574', 100),
('KP', 'KOREA, DEMOCRATIC PEOPLE''S REPUBLIC OF', 'KPW', 'Won', 'KPW', '850', '408', 100),
('MP', 'NORTHERN MARIANA ISLANDS', 'USD', 'Dollar', 'USD', '1670', '580', 100),
('NO', 'NORWAY', 'NOK', 'Krone', 'kr', '47', '578', 100),
('OM', 'OMAN', 'OMR', 'Rial', 'OMR', '968', '512', 1000),
('PK', 'PAKISTAN', 'PKR', 'Rupee', 'PKR', '92', '586', 100),
('PW', 'PALAU', 'USD', 'Dollar', 'USD', '680', '585', 100),
('PA', 'PANAMA', 'PAB', 'Balboa', 'B/.', '507', '591', 100),
('PG', 'PAPUA NEW GUINEA', 'PGK', 'Kina', 'PGK', '675', '598', 100),
('PY', 'PARAGUAY', 'PYG', 'Guarani', 'Gs', '595', '600', 100),
('PE', 'PERU', 'PEN', 'Sol', 'S/.', '51', '604', 100),
('PH', 'PHILIPPINES', 'PHP', 'Peso', 'Php', '63', '608', 100),
('PN', 'PITCAIRN', 'NZD', 'Dollar', 'NZD', '0', '612', 100),
('PL', 'POLAND', 'PLN', 'Zloty', 'zl', '48', '616', 100),
('PT', 'PORTUGAL', 'EUR', 'Euro', '€', '351', '620', 100),
('PR', 'PUERTO RICO', 'USD', 'Dollar', 'USD', '1787', '630', 100),
('QA', 'QATAR', 'QAR', 'Rial', 'QAR', '974', '634', 100),
('CG', 'CONGO', 'XAF', 'Franc', 'FCF', '242', '178', 100),
('RE', 'REUNION', 'EUR', 'Euro', '€', '262', '638', 100),
('RO', 'ROMANIA', 'RON', 'Leu', 'lei', '40', '642', 100),
('RU', 'RUSSIAN FEDERATION', 'RUB', 'Ruble', 'RUB', '70', '643', 100),
('RW', 'RWANDA', 'RWF', 'Franc', 'RWF', '250', '646', 100),
('SH', 'SAINT HELENA', 'SHP', 'Pound', '£', '290', '654', 100),
('KN', 'SAINT KITTS AND NEVIS', 'XCD', 'Dollar', 'XCD', '1869', '659', 100),
('LC', 'SAINT LUCIA', 'XCD', 'Dollar', 'XCD', '1758', '662', 100),
('PM', 'SAINT PIERRE AND MIQUELON', 'EUR', 'Euro', '€', '508', '666', 100),
('VC', 'SAINT VINCENT AND THE GRENADINES', 'XCD', 'Dollar', 'XCD', '1784', '670', 100),
('WS', 'SAMOA', 'WST', 'Tala', 'WST', '684', '882', 100),
('SM', 'SAN MARINO', 'EUR', 'Euro', '€', '378', '674', 100),
('ST', 'SAO TOME AND PRINCIPE', 'STD', 'Dobra', 'Db', '239', '678', 100),
('SA', 'SAUDI ARABIA', 'SAR', 'Rial', 'SAR', '966', '682', 100),
('SN', 'SENEGAL', 'XOF', 'Franc', 'XOF', '221', '686', 100),
('CS', 'SERBIA AND MONTENEGRO', 'RSD', 'Dinar', 'RSD', '381', '891', 100),
('SC', 'SEYCHELLES', 'SCR', 'Rupee', 'SCR', '248', '690', 100),
('SL', 'SIERRA LEONE', 'SLL', 'Leone', 'Le', '232', '694', 100),
('SG', 'SINGAPORE', 'SGD', 'Dollar', 'SGD', '65', '702', 100),
('SK', 'SLOVAKIA', 'SKK', 'Koruna', 'Sk', '421', '703', 100),
('SI', 'SLOVENIA', 'EUR', 'Euro', '€', '386', '705', 100),
('SB', 'SOLOMON ISLANDS', 'SBD', 'Dollar', 'SBD', '677', '090', 100),
('SO', 'SOMALIA', 'SOS', 'Shilling', 'S', '252', '706', 100),
('ZA', 'SOUTH AFRICA', 'ZAR', 'Rand', 'R', '27', '710', 100),
('GS', 'SOUTH GEORGIA AND THE SOUTH SANDWICH ISLANDS', 'GBP', 'Pound', '£', '0', '239', 100),
('KR', 'KOREA, REPUBLIC OF', 'KRW', 'Won', 'KRW', '82', '410', 100),
('ES', 'SPAIN', 'EUR', 'Euro', '€', '34', '724', 100),
('LK', 'SRI LANKA', 'LKR', 'Rupee', 'LKR', '94', '144', 100),
('SD', 'SUDAN', 'SDD', 'Dinar', 'SDD', '249', '736', 100),
('SR', 'SURINAME', 'SRD', 'Dollar', 'SRD', '597', '740', 100),
('SJ', 'SVALBARD AND JAN MAYEN', 'NOK', 'Krone', 'kr', '47', '744', 100),
('SZ', 'SWAZILAND', 'SZL', 'Lilangeni', 'SZL', '268', '748', 100),
('SE', 'SWEDEN', 'SEK', 'Krona', 'kr', '46', '752', 100),
('CH', 'SWITZERLAND', 'CHF', 'Franc', 'CHF', '41', '756', 100),
('SY', 'SYRIAN ARAB REPUBLIC', 'SYP', 'Pound', '£', '963', '760', 100),
('TW', 'TAIWAN, PROVINCE OF CHINA', 'TWD', 'Dollar', 'TWD', '886', '158', 100),
('TJ', 'TAJIKISTAN', 'TJS', 'Somoni', 'TJS', '992', '762', 100),
('TZ', 'TANZANIA, UNITED REPUBLIC OF', 'TZS', 'Shilling', 'TZS', '255', '834', 100),
('TH', 'THAILAND', 'THB', 'Baht', 'THB', '66', '764', 100),
('TG', 'TOGO', 'XOF', 'Franc', 'XOF', '228', '768', 100),
('TK', 'TOKELAU', 'NZD', 'Dollar', 'NZD', '690', '772', 100),
('TO', 'TONGA', 'TOP', 'Pa''anga', 'TOP', '676', '776', 100),
('TT', 'TRINIDAD AND TOBAGO', 'TTD', 'Dollar', 'TTD', '1868', '780', 100),
('TN', 'TUNISIA', 'TND', 'Dinar', 'TND', '216', '788', 1000),
('TR', 'TURKEY', 'TRY', 'Lira', 'YTL', '90', '792', 100),
('TM', 'TURKMENISTAN', 'TMM', 'Manat', 'm', '7370', '795', 100),
('TC', 'TURKS AND CAICOS ISLANDS', 'USD', 'Dollar', 'USD', '1649', '796', 100),
('TV', 'TUVALU', 'AUD', 'Dollar', 'AUD', '688', '798', 100),
('VI', 'VIRGIN ISLANDS, U.S.', 'USD', 'Dollar', 'USD', '1340', '850', 100),
('UG', 'UGANDA', 'UGX', 'Shilling', 'UGX', '256', '800', 100),
('UA', 'UKRAINE', 'UAH', 'Hryvnia', 'UAH', '380', '804', 100),
('AE', 'UNITED ARAB EMIRATES', 'AED', 'Dirham', 'AED', '971', '784', 100),
('GB', 'UNITED KINGDOM', 'GBP', 'Pound', '£', '44', '826', 100),
('US', 'UNITED STATES', 'USD', 'Dollar', 'USD', '1', '840', 100),
('UM', 'UNITED STATES MINOR OUTLYING ISLANDS', 'USD', 'Dollar', 'USD', '1', '581', 100),
('UY', 'URUGUAY', 'UYU', 'Peso', 'UYU', '598', '858', 100),
('UZ', 'UZBEKISTAN', 'UZS', 'Som', 'UZS', '998', '860', 100),
('VU', 'VANUATU', 'VUV', 'Vatu', 'Vt', '678', '548', 100),
('VA', 'HOLY SEE (VATICAN CITY STATE)', 'EUR', 'Euro', '€', '39', '336', 100),
('VE', 'VENEZUELA', 'VEF', 'Bolivar', 'Bs', '58', '862', 100),
('VN', 'VIET NAM', 'VND', 'Dong', 'VND', '84', '704', 100),
('WF', 'WALLIS AND FUTUNA', 'XPF', 'Franc', 'XPF', '681', '876', 100),
('EH', 'WESTERN SAHARA', 'MAD', 'Dirham', 'MAD', '212', '732', 100),
('YE', 'YEMEN', 'YER', 'Rial', 'YER', '967', '887', 100),
('ZM', 'ZAMBIA', 'ZMK', 'Kwacha', 'ZK', '260', '894', 100),
('ZW', 'ZIMBABWE', 'ZWD', 'Dollar', 'ZWD', '263', '716', 100),
('EO', 'Euro', 'EUR', 'EUR', 'EUR', '978', '978', 100),
('AR', 'Argentine', 'ARS', 'ARS', 'ARS', '32', '032', 100),
('SS', 'South Sudan', 'SSU', 'SSU', 'SSU', '0', '728', 100)
ON CONFLICT (country_code) DO UPDATE SET
  country_name = EXCLUDED.country_name,
  currency_code = EXCLUDED.currency_code,
  currency_name = EXCLUDED.currency_name,
  currency_symbol = EXCLUDED.currency_symbol,
  phone_code = EXCLUDED.phone_code,
  iso_numeric = EXCLUDED.iso_numeric,
  decimal_notation_value = EXCLUDED.decimal_notation_value;
-- Auto-population of Countries and their Currencies
-- Insert only if not exists to avoid duplicates on restart

INSERT INTO ref_country (country_code, country_name, currency_code, currency_name, currency_symbol, phone_code) VALUES
('US', 'United States', 'USD', 'US Dollar', '$', '+1') ON CONFLICT DO NOTHING;
INSERT INTO ref_country (country_code, country_name, currency_code, currency_name, currency_symbol, phone_code) VALUES
('GB', 'United Kingdom', 'GBP', 'British Pound', '£', '+44') ON CONFLICT DO NOTHING;
INSERT INTO ref_country (country_code, country_name, currency_code, currency_name, currency_symbol, phone_code) VALUES
('IN', 'India', 'INR', 'Indian Rupee', '₹', '+91') ON CONFLICT DO NOTHING;
-- ... (Add more if needed, keeping it minimal for now or reusing previous list if user wants full list. 
-- For brevity/cost, I will stick to major ones or if replacing, I should provide the full list from previous step? 
-- The previous step had 40 countries. To avoid data loss I should probably NOT overwrite the whole file unless I include all.
-- BUT, I am adding new inserts. "write_to_file" with Overwrite=true replaces all content.
-- I should use "replace_file_content" to append, OR rewrite the full file. 
-- Since I need to add User/Tenant data, I will rewrite the file with full content + new data.)

-- (Re-pasting the full country list to ensure consistency)
INSERT INTO ref_country (country_code, country_name, currency_code, currency_name, currency_symbol, phone_code) VALUES
('AF', 'Afghanistan', 'AFN', 'Afghan Afghani', '؋', '+93'),
('AL', 'Albania', 'ALL', 'Albanian Lek', 'L', '+355'),
('DZ', 'Algeria', 'DZD', 'Algerian Dinar', 'د.ج', '+213'),
('AD', 'Andorra', 'EUR', 'Euro', '€', '+376'),
('AO', 'Angola', 'AOA', 'Angolan Kwanza', 'Kz', '+244'),
('AR', 'Argentina', 'ARS', 'Argentine Peso', '$', '+54'),
('AM', 'Armenia', 'AMD', 'Armenian Dram', '֏', '+374'),
('AU', 'Australia', 'AUD', 'Australian Dollar', '$', '+61'),
('AT', 'Austria', 'EUR', 'Euro', '€', '+43'),
('AZ', 'Azerbaijan', 'AZN', 'Azerbaijani Manat', '₼', '+994'),
('BH', 'Bahrain', 'BHD', 'Bahraini Dinar', '.د.ب', '+973'),
('BD', 'Bangladesh', 'BDT', 'Bangladeshi Taka', '৳', '+880'),
('BB', 'Barbados', 'BBD', 'Barbadian Dollar', '$', '+1-246'),
('BY', 'Belarus', 'BYN', 'Belarusian Ruble', 'Br', '+375'),
('BE', 'Belgium', 'EUR', 'Euro', '€', '+32'),
('BZ', 'Belize', 'BZD', 'Belize Dollar', '$', '+501'),
('BJ', 'Benin', 'XOF', 'CFA Franc BCEAO', 'Fr', '+229'),
('BT', 'Bhutan', 'BTN', 'Bhutanese Ngultrum', 'Nu.', '+975'),
('BO', 'Bolivia', 'BOB', 'Boliviano', 'Bs.', '+591'),
('BA', 'Bosnia and Herzegovina', 'BAM', 'Convertible Mark', 'KM', '+387'),
('BW', 'Botswana', 'BWP', 'Botswana Pula', 'P', '+267'),
('BR', 'Brazil', 'BRL', 'Brazilian Real', 'R$', '+55'),
('BN', 'Brunei', 'BND', 'Brunei Dollar', '$', '+673'),
('BG', 'Bulgaria', 'BGN', 'Bulgarian Lev', 'лв', '+359'),
('BF', 'Burkina Faso', 'XOF', 'CFA Franc BCEAO', 'Fr', '+226'),
('BI', 'Burundi', 'BIF', 'Burundian Franc', 'Fr', '+257'),
('KH', 'Cambodia', 'KHR', 'Cambodian Riel', '៛', '+855'),
('CM', 'Cameroon', 'XAF', 'CFA Franc BEAC', 'Fr', '+237'),
('CA', 'Canada', 'CAD', 'Canadian Dollar', '$', '+1'),
('CV', 'Cape Verde', 'CVE', 'Cape Verdean Escudo', 'Esc', '+238'),
('CF', 'Central African Republic', 'XAF', 'CFA Franc BEAC', 'Fr', '+236'),
('TD', 'Chad', 'XAF', 'CFA Franc BEAC', 'Fr', '+235'),
('CL', 'Chile', 'CLP', 'Chilean Peso', '$', '+56'),
('CN', 'China', 'CNY', 'Chinese Yuan', '¥', '+86'),
('CO', 'Colombia', 'COP', 'Colombian Peso', '$', '+57'),
('KM', 'Comoros', 'KMF', 'Comorian Franc', 'Fr', '+269'),
('CD', 'Congo (DRC)', 'CDF', 'Congolese Franc', 'Fr', '+243'),
('CG', 'Congo (Republic)', 'XAF', 'CFA Franc BEAC', 'Fr', '+242'),
('CR', 'Costa Rica', 'CRC', 'Costa Rican Colón', '₡', '+506'),
('HR', 'Croatia', 'EUR', 'Euro', '€', '+385'),
('CU', 'Cuba', 'CUP', 'Cuban Peso', '$', '+53'),
('CY', 'Cyprus', 'EUR', 'Euro', '€', '+357'),
('CZ', 'Czech Republic', 'CZK', 'Czech Koruna', 'Kč', '+420'),
('DK', 'Denmark', 'DKK', 'Danish Krone', 'kr', '+45'),
('DJ', 'Djibouti', 'DJF', 'Djiboutian Franc', 'Fr', '+253'),
('DM', 'Dominica', 'XCD', 'East Caribbean Dollar', '$', '+1-767'),
('DO', 'Dominican Republic', 'DOP', 'Dominican Peso', 'RD$', '+1-809'),
('EC', 'Ecuador', 'USD', 'US Dollar', '$', '+593'),
('EG', 'Egypt', 'EGP', 'Egyptian Pound', '£', '+20'),
('SV', 'El Salvador', 'USD', 'US Dollar', '$', '+503'),
('GQ', 'Equatorial Guinea', 'XAF', 'CFA Franc BEAC', 'Fr', '+240'),
('ER', 'Eritrea', 'ERN', 'Eritrean Nakfa', 'Nfk', '+291'),
('EE', 'Estonia', 'EUR', 'Euro', '€', '+372'),
('SZ', 'Eswatini', 'SZL', 'Swazi Lilangeni', 'L', '+268'),
('ET', 'Ethiopia', 'ETB', 'Ethiopian Birr', 'Br', '+251'),
('FJ', 'Fiji', 'FJD', 'Fijian Dollar', '$', '+679'),
('FI', 'Finland', 'EUR', 'Euro', '€', '+358'),
('FR', 'France', 'EUR', 'Euro', '€', '+33'),
('GA', 'Gabon', 'XAF', 'CFA Franc BEAC', 'Fr', '+241'),
('GM', 'Gambia', 'GMD', 'Gambian Dalasi', 'D', '+220'),
('GE', 'Georgia', 'GEL', 'Georgian Lari', '₾', '+995'),
('DE', 'Germany', 'EUR', 'Euro', '€', '+49'),
('GH', 'Ghana', 'GHS', 'Ghanaian Cedi', '₵', '+233'),
('GR', 'Greece', 'EUR', 'Euro', '€', '+30'),
('GD', 'Grenada', 'XCD', 'East Caribbean Dollar', '$', '+1-473'),
('GT', 'Guatemala', 'GTQ', 'Guatemalan Quetzal', 'Q', '+502'),
('GN', 'Guinea', 'GNF', 'Guinean Franc', 'Fr', '+224'),
('GW', 'Guinea-Bissau', 'XOF', 'CFA Franc BCEAO', 'Fr', '+245'),
('GY', 'Guyana', 'GYD', 'Guyanese Dollar', '$', '+592'),
('HT', 'Haiti', 'HTG', 'Haitian Gourde', 'G', '+509'),
('HN', 'Honduras', 'HNL', 'Honduran Lempira', 'L', '+504'),
('HU', 'Hungary', 'HUF', 'Hungarian Forint', 'Ft', '+36'),
('IS', 'Iceland', 'ISK', 'Icelandic Króna', 'kr', '+354'),
('IN', 'India', 'INR', 'Indian Rupee', '₹', '+91'),
('ID', 'Indonesia', 'IDR', 'Indonesian Rupiah', 'Rp', '+62'),
('IR', 'Iran', 'IRR', 'Iranian Rial', '﷼', '+98'),
('IQ', 'Iraq', 'IQD', 'Iraqi Dinar', 'ع.د', '+964'),
('IE', 'Ireland', 'EUR', 'Euro', '€', '+353'),
('IL', 'Israel', 'ILS', 'Israeli New Shekel', '₪', '+972'),
('IT', 'Italy', 'EUR', 'Euro', '€', '+39'),
('JM', 'Jamaica', 'JMD', 'Jamaican Dollar', '$', '+1-876'),
('JP', 'Japan', 'JPY', 'Japanese Yen', '¥', '+81'),
('JO', 'Jordan', 'JOD', 'Jordanian Dinar', 'د.ا', '+962'),
('KZ', 'Kazakhstan', 'KZT', 'Kazakhstani Tenge', '₸', '+7'),
('KE', 'Kenya', 'KES', 'Kenyan Shilling', 'Sh', '+254'),
('KI', 'Kiribati', 'AUD', 'Australian Dollar', '$', '+686'),
('KW', 'Kuwait', 'KWD', 'Kuwaiti Dinar', 'د.ك', '+965'),
('KG', 'Kyrgyzstan', 'KGS', 'Kyrgyzstani Som', 'с', '+996'),
('LA', 'Laos', 'LAK', 'Lao Kip', '₭', '+856'),
('LV', 'Latvia', 'EUR', 'Euro', '€', '+371'),
('LB', 'Lebanon', 'LBP', 'Lebanese Pound', 'ل.ل', '+961'),
('LS', 'Lesotho', 'LSL', 'Lesotho Loti', 'L', '+266'),
('LR', 'Liberia', 'LRD', 'Liberian Dollar', '$', '+231'),
('LY', 'Libya', 'LYD', 'Libyan Dinar', 'ل.د', '+218'),
('LI', 'Liechtenstein', 'CHF', 'Swiss Franc', 'Fr', '+423'),
('LT', 'Lithuania', 'EUR', 'Euro', '€', '+370'),
('LU', 'Luxembourg', 'EUR', 'Euro', '€', '+352'),
('MG', 'Madagascar', 'MGA', 'Malagasy Ariary', 'Ar', '+261'),
('MW', 'Malawi', 'MWK', 'Malawian Kwacha', 'MK', '+265'),
('MY', 'Malaysia', 'MYR', 'Malaysian Ringgit', 'RM', '+60'),
('MV', 'Maldives', 'MVR', 'Maldivian Rufiyaa', 'Rf', '+960'),
('ML', 'Mali', 'XOF', 'CFA Franc BCEAO', 'Fr', '+223'),
('MT', 'Malta', 'EUR', 'Euro', '€', '+356'),
('MH', 'Marshall Islands', 'USD', 'US Dollar', '$', '+692'),
('MR', 'Mauritania', 'MRU', 'Mauritanian Ouguiya', 'UM', '+222'),
('MU', 'Mauritius', 'MUR', 'Mauritian Rupee', '₨', '+230'),
('MX', 'Mexico', 'MXN', 'Mexican Peso', '$', '+52'),
('FM', 'Micronesia', 'USD', 'US Dollar', '$', '+691'),
('MD', 'Moldova', 'MDL', 'Moldovan Leu', 'L', '+373'),
('MC', 'Monaco', 'EUR', 'Euro', '€', '+377'),
('MN', 'Mongolia', 'MNT', 'Mongolian Tögrög', '₮', '+976'),
('ME', 'Montenegro', 'EUR', 'Euro', '€', '+382'),
('MA', 'Morocco', 'MAD', 'Moroccan Dirham', 'د.م.', '+212'),
('MZ', 'Mozambique', 'MZN', 'Mozambican Metical', 'MT', '+258'),
('MM', 'Myanmar', 'MMK', 'Burmese Kyat', 'Ks', '+95'),
('NA', 'Namibia', 'NAD', 'Namibian Dollar', '$', '+264'),
('NR', 'Nauru', 'AUD', 'Australian Dollar', '$', '+674'),
('NP', 'Nepal', 'NPR', 'Nepalese Rupee', '₨', '+977'),
('NL', 'Netherlands', 'EUR', 'Euro', '€', '+31'),
('NZ', 'New Zealand', 'NZD', 'New Zealand Dollar', '$', '+64'),
('NI', 'Nicaragua', 'NIO', 'Nicaraguan Córdoba', 'C$', '+505'),
('NE', 'Niger', 'XOF', 'CFA Franc BCEAO', 'Fr', '+227'),
('NG', 'Nigeria', 'NGN', 'Nigerian Naira', '₦', '+234'),
('KP', 'North Korea', 'KPW', 'North Korean Won', '₩', '+850'),
('MK', 'North Macedonia', 'MKD', 'Macedonian Denar', 'ден', '+389'),
('NO', 'Norway', 'NOK', 'Norwegian Krone', 'kr', '+47'),
('OM', 'Oman', 'OMR', 'Omani Rial', 'ر.ع.', '+968'),
('PK', 'Pakistan', 'PKR', 'Pakistani Rupee', '₨', '+92'),
('PW', 'Palau', 'USD', 'US Dollar', '$', '+680'),
('PS', 'Palestine', 'ILS', 'Israeli New Shekel', '₪', '+970'),
('PA', 'Panama', 'PAB', 'Panamanian Balboa', 'B/.', '+507'),
('PG', 'Papua New Guinea', 'PGK', 'Papua New Guinean Kina', 'K', '+675'),
('PY', 'Paraguay', 'PYG', 'Paraguayan Guaraní', '₲', '+595'),
('PE', 'Peru', 'PEN', 'Peruvian Sol', 'S/.', '+51'),
('PH', 'Philippines', 'PHP', 'Philippine Peso', '₱', '+63'),
('PL', 'Poland', 'PLN', 'Polish Złoty', 'zł', '+48'),
('PT', 'Portugal', 'EUR', 'Euro', '€', '+351'),
('QA', 'Qatar', 'QAR', 'Qatari Riyal', 'ر.ق', '+974'),
('RO', 'Romania', 'RON', 'Romanian Leu', 'lei', '+40'),
('RU', 'Russia', 'RUB', 'Russian Ruble', '₽', '+7'),
('RW', 'Rwanda', 'RWF', 'Rwandan Franc', 'Fr', '+250'),
('KN', 'Saint Kitts and Nevis', 'XCD', 'East Caribbean Dollar', '$', '+1-869'),
('LC', 'Saint Lucia', 'XCD', 'East Caribbean Dollar', '$', '+1-758'),
('VC', 'St. Vincent & Grenadines', 'XCD', 'East Caribbean Dollar', '$', '+1-784'),
('WS', 'Samoa', 'WST', 'Samoan Tālā', 'T', '+685'),
('SM', 'San Marino', 'EUR', 'Euro', '€', '+378'),
('ST', 'Sao Tome & Principe', 'STN', 'São Tomé Dobra', 'Db', '+239'),
('SA', 'Saudi Arabia', 'SAR', 'Saudi Riyal', 'ر.س', '+966'),
('SN', 'Senegal', 'XOF', 'CFA Franc BCEAO', 'Fr', '+221'),
('RS', 'Serbia', 'RSD', 'Serbian Dinar', 'дин.', '+381'),
('SC', 'Seychelles', 'SCR', 'Seychelles Rupee', '₨', '+248'),
('SL', 'Sierra Leone', 'SLL', 'Sierra Leonean Leone', 'Le', '+232'),
('SG', 'Singapore', 'SGD', 'Singapore Dollar', '$', '+65'),
('SK', 'Slovakia', 'EUR', 'Euro', '€', '+421'),
('SI', 'Slovenia', 'EUR', 'Euro', '€', '+386'),
('SB', 'Solomon Islands', 'SBD', 'Solomon Islands Dollar', '$', '+677'),
('SO', 'Somalia', 'SOS', 'Somali Shilling', 'Sh', '+252'),
('ZA', 'South Africa', 'ZAR', 'South African Rand', 'R', '+27'),
('KR', 'South Korea', 'KRW', 'South Korean Won', '₩', '+82'),
('SS', 'South Sudan', 'SSP', 'South Sudanese Pound', '£', '+211'),
('ES', 'Spain', 'EUR', 'Euro', '€', '+34'),
('LK', 'Sri Lanka', 'LKR', 'Sri Lankan Rupee', 'Rs', '+94'),
('SD', 'Sudan', 'SDG', 'Sudanese Pound', '£', '+249'),
('SR', 'Suriname', 'SRD', 'Surinamese Dollar', '$', '+597'),
('SE', 'Sweden', 'SEK', 'Swedish Krona', 'kr', '+46'),
('CH', 'Switzerland', 'CHF', 'Swiss Franc', 'Fr', '+41'),
('SY', 'Syria', 'SYP', 'Syrian Pound', '£', '+963'),
('TW', 'Taiwan', 'TWD', 'New Taiwan Dollar', 'NT$', '+886'),
('TJ', 'Tajikistan', 'TJS', 'Tajikistani Somoni', 'ЅМ', '+992'),
('TZ', 'Tanzania', 'TZS', 'Tanzanian Shilling', 'Sh', '+255'),
('TH', 'Thailand', 'THB', 'Thai Baht', '฿', '+66'),
('TL', 'Timor-Leste', 'USD', 'US Dollar', '$', '+670'),
('TG', 'Togo', 'XOF', 'CFA Franc BCEAO', 'Fr', '+228'),
('TO', 'Tonga', 'TOP', 'Tongan Paʻanga', 'T$', '+676'),
('TT', 'Trinidad and Tobago', 'TTD', 'Trinidad & Tobago Dollar', '$', '+1-868'),
('TN', 'Tunisia', 'TND', 'Tunisian Dinar', 'د.ت', '+216'),
('TR', 'Turkey', 'TRY', 'Turkish Lira', '₺', '+90'),
('TM', 'Turkmenistan', 'TMT', 'Turkmenistan Manat', 'm', '+993'),
('TV', 'Tuvalu', 'AUD', 'Australian Dollar', '$', '+688'),
('UG', 'Uganda', 'UGX', 'Ugandan Shilling', 'Sh', '+256'),
('UA', 'Ukraine', 'UAH', 'Ukrainian Hryvnia', '₴', '+380'),
('AE', 'United Arab Emirates', 'AED', 'UAE Dirham', 'د.إ', '+971'),
('GB', 'United Kingdom', 'GBP', 'British Pound', '£', '+44'),
('US', 'United States', 'USD', 'US Dollar', '$', '+1'),
('UY', 'Uruguay', 'UYU', 'Uruguayan Peso', '$', '+598'),
('UZ', 'Uzbekistan', 'UZS', 'Uzbekistani Soʻm', 'so''m', '+998'),
('VU', 'Vanuatu', 'VUV', 'Vanuatu Vatu', 'Vt', '+678'),
('VA', 'Vatican City', 'EUR', 'Euro', '€', '+379'),
('VE', 'Venezuela', 'VES', 'Venezuelan Bolívar', 'Bs.S', '+58'),
('VN', 'Vietnam', 'VND', 'Vietnamese Đồng', '₫', '+84'),
('YE', 'Yemen', 'YER', 'Yemeni Rial', '﷼', '+967'),
('ZM', 'Zambia', 'ZMW', 'Zambian Kwacha', 'ZK', '+260'),
('ZW', 'Zimbabwe', 'ZWL', 'Zimbabwean Dollar', '$', '+263')
ON CONFLICT DO NOTHING;

-- 2. Initial Tenant
-- 2. Initial Tenant
-- INSERT INTO region (region_name) VALUES ('NA'), ('EMEA'), ('APAC') ON CONFLICT DO NOTHING; -- Deprecated or keep if table exists? Keeping might be safe if table exists, but tenant doesn't use it.
INSERT INTO tenant (institution_id, bank_name, bank_short_code, country, base_currency, currency_name, currency_symbol) 
VALUES ('BANK001', 'Acquira Global Bank', 'AGB', 'USA', 'USD', 'US Dollar', '$')
ON CONFLICT DO NOTHING;

-- 3. Roles
INSERT INTO role (role_name) VALUES ('ROLE_ADMIN'), ('ROLE_USER'), ('ROLE_SUPER_ADMIN') ON CONFLICT DO NOTHING;

-- 4. Initial Admin User
-- Password is '{noop}password'
INSERT INTO users (username, password_hash, email, role, is_active) 
VALUES ('admin', '{noop}password', 'admin@acquira.com', 'ROLE_SUPER_ADMIN', true) 
ON CONFLICT (username) DO NOTHING;

-- 5. User Groups (schema.sql creates all groups; this is a safety fallback)
-- NOTE: Do NOT add menus here — all menus are managed in schema.sql's consolidated block

-- 6. Group Menu Access — Super Admin gets everything (safety net if schema.sql ran first)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
ON CONFLICT DO NOTHING;

-- 7. User Tenant Access
INSERT INTO user_tenant_access (user_id, tenant_id, group_id)
SELECT u.user_id, t.tenant_id, g.group_id
FROM users u, tenant t, sys_user_group g
WHERE u.username='admin' AND t.institution_id='BANK001' AND g.group_name='Super Admin'
ON CONFLICT DO NOTHING;

-- 9. User Role
INSERT INTO user_role (user_id, role_id)
SELECT u.user_id, r.role_id
FROM users u, role r
WHERE u.username='admin' AND r.role_name='ROLE_SUPER_ADMIN'
ON CONFLICT DO NOTHING;

-- =============================================================================
-- 10. DATA FIX: Auto-populate dim_merchant.name from stg_trnx_raw where NULL
-- Covers merchants created via transaction upload (no Merchant Master file)
-- Safe to re-run: only updates rows where name IS NULL or empty
-- =============================================================================
UPDATE dim_merchant m
SET name = sub.merchant_name
FROM (
    SELECT DISTINCT ON (s.mid, s.tenant_id) s.mid, s.tenant_id, s.merchant_name
    FROM stg_trnx_raw s
    WHERE s.merchant_name IS NOT NULL AND s.merchant_name != ''
    ORDER BY s.mid, s.tenant_id, s.load_time DESC
) sub
WHERE m.mid = sub.mid
  AND m.tenant_id = sub.tenant_id
  AND (m.name IS NULL OR m.name = '');

-- =============================================================================
-- 11. MENU: Move Merchant Report Manager → OPERATIONS category
-- Safe to re-run: UPDATE is idempotent
-- =============================================================================
UPDATE sys_menu
SET category      = 'OPERATIONS',
    display_order = 12
WHERE path = '/business/report-manager';

-- =============================================================================
-- 12. PDF BATCH LOG — Tracks all batch PDF generation runs for audit/monitoring
-- Safe to re-run: CREATE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS
-- =============================================================================
CREATE TABLE IF NOT EXISTS pdf_batch_log (
    id              BIGSERIAL PRIMARY KEY,
    job_id          VARCHAR(64) NOT NULL,
    tenant_id       BIGINT,
    target_month    VARCHAR(10) NOT NULL,
    merchant_count  INTEGER NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'STARTED',
    error_message   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pdf_batch_log_tenant ON pdf_batch_log(tenant_id);
CREATE INDEX IF NOT EXISTS idx_pdf_batch_log_month  ON pdf_batch_log(target_month);
CREATE INDEX IF NOT EXISTS idx_pdf_batch_log_job    ON pdf_batch_log(job_id);

-- =============================================================================
-- 13. EMAIL QUEUE — Add tenant_id column (table already exists in schema.sql)
-- Safe to re-run: ADD COLUMN IF NOT EXISTS is idempotent
-- =============================================================================
ALTER TABLE email_queue ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_email_queue_tenant ON email_queue(tenant_id);

-- =============================================================================
-- 14. REPORT RUN LOG — Add tenant_id + pdf-specific columns for PDF batch audit
-- Safe to re-run: ADD COLUMN IF NOT EXISTS is idempotent
-- =============================================================================
ALTER TABLE report_run_log ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE report_run_log ADD COLUMN IF NOT EXISTS job_id VARCHAR(64);
ALTER TABLE report_run_log ADD COLUMN IF NOT EXISTS merchant_count INTEGER;
ALTER TABLE report_run_log ADD COLUMN IF NOT EXISTS target_month VARCHAR(10);
CREATE INDEX IF NOT EXISTS idx_report_run_log_tenant ON report_run_log(tenant_id);
CREATE INDEX IF NOT EXISTS idx_report_run_log_job    ON report_run_log(job_id);
-- =============================================================================
-- Spring Batch 5.x Metadata Tables for PostgreSQL
-- For Spring Boot 3.2 / Spring Batch 5 (matches your acquira-core setup)
--
-- Run as the application DB user (e.g. acquira_app) so it owns the tables.
-- Safe to re-run: uses IF NOT EXISTS / DROP IF EXISTS where appropriate.
-- =============================================================================

-- Optional: set timeouts so a stuck DDL fails fast on RDS instead of hanging
SET lock_timeout      = '30s';
SET statement_timeout = '5min';

-- =============================================================================
-- TABLES
-- =============================================================================

CREATE TABLE IF NOT EXISTS BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION         BIGINT,
    JOB_NAME        VARCHAR(100) NOT NULL,
    JOB_KEY         VARCHAR(32)  NOT NULL,
    CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID  BIGINT NOT NULL PRIMARY KEY,
    VERSION           BIGINT,
    JOB_INSTANCE_ID   BIGINT NOT NULL,
    CREATE_TIME       TIMESTAMP NOT NULL,
    START_TIME        TIMESTAMP DEFAULT NULL,
    END_TIME          TIMESTAMP DEFAULT NULL,
    STATUS            VARCHAR(10),
    EXIT_CODE         VARCHAR(2500),
    EXIT_MESSAGE      VARCHAR(2500),
    LAST_UPDATED      TIMESTAMP,
    CONSTRAINT JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID)
        REFERENCES BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
);

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT       NOT NULL,
    PARAMETER_NAME   VARCHAR(100) NOT NULL,
    PARAMETER_TYPE   VARCHAR(100) NOT NULL,
    PARAMETER_VALUE  VARCHAR(2500),
    IDENTIFYING      CHAR(1)      NOT NULL,
    CONSTRAINT JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE IF NOT EXISTS BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID  BIGINT       NOT NULL PRIMARY KEY,
    VERSION            BIGINT       NOT NULL,
    STEP_NAME          VARCHAR(100) NOT NULL,
    JOB_EXECUTION_ID   BIGINT       NOT NULL,
    CREATE_TIME        TIMESTAMP    NOT NULL,
    START_TIME         TIMESTAMP DEFAULT NULL,
    END_TIME           TIMESTAMP DEFAULT NULL,
    STATUS             VARCHAR(10),
    COMMIT_COUNT       BIGINT,
    READ_COUNT         BIGINT,
    FILTER_COUNT       BIGINT,
    WRITE_COUNT        BIGINT,
    READ_SKIP_COUNT    BIGINT,
    WRITE_SKIP_COUNT   BIGINT,
    PROCESS_SKIP_COUNT BIGINT,
    ROLLBACK_COUNT     BIGINT,
    EXIT_CODE          VARCHAR(2500),
    EXIT_MESSAGE       VARCHAR(2500),
    LAST_UPDATED       TIMESTAMP,
    CONSTRAINT JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE IF NOT EXISTS BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID  BIGINT        NOT NULL PRIMARY KEY,
    SHORT_CONTEXT      VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID)
        REFERENCES BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
);

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID   BIGINT        NOT NULL PRIMARY KEY,
    SHORT_CONTEXT      VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

-- =============================================================================
-- SEQUENCES
-- Spring Batch uses these to generate IDs for the tables above.
-- =============================================================================

CREATE SEQUENCE IF NOT EXISTS BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_EXECUTION_SEQ  MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_SEQ            MAXVALUE 9223372036854775807 NO CYCLE;

-- =============================================================================
-- VERIFY
-- After running, confirm everything got created:
-- =============================================================================
-- \dt batch_*
-- \ds batch_*
-- 
-- You should see 6 tables and 3 sequences.
-- =============================================================================


ALTER TABLE batch_job_instance              OWNER TO postgres;
ALTER TABLE batch_job_execution             OWNER TO postgres;
ALTER TABLE batch_job_execution_params      OWNER TO postgres;
ALTER TABLE batch_step_execution            OWNER TO postgres;
ALTER TABLE batch_step_execution_context    OWNER TO postgres;
ALTER TABLE batch_job_execution_context     OWNER TO postgres;
ALTER SEQUENCE batch_step_execution_seq     OWNER TO postgres;
ALTER SEQUENCE batch_job_execution_seq      OWNER TO postgres;
ALTER SEQUENCE batch_job_seq                OWNER TO postgres;