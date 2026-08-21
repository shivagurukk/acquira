-- ==================================================================================
-- MIGRATION: Add ref_card_scheme table + ref_country columns for TGEN509 processing
-- Run this ONCE on your existing database.
-- Safe to re-run: uses IF NOT EXISTS and ON CONFLICT DO NOTHING.
-- ==================================================================================

-- 1. Add missing columns to ref_country (if they don't already exist)
ALTER TABLE ref_country ADD COLUMN IF NOT EXISTS iso_numeric VARCHAR(3);
ALTER TABLE ref_country ADD COLUMN IF NOT EXISTS decimal_notation_value INTEGER DEFAULT 100;

-- 2. Create ref_card_scheme table
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

-- 3. Seed ref_card_scheme (17 rows matching AFSU.CardSchemes)
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

-- 4. Seed iso_numeric + decimal_notation_value for common currencies
UPDATE ref_country SET iso_numeric = '048', decimal_notation_value = 1000 WHERE currency_code = 'BHD';
UPDATE ref_country SET iso_numeric = '840', decimal_notation_value = 100  WHERE currency_code = 'USD';
UPDATE ref_country SET iso_numeric = '826', decimal_notation_value = 100  WHERE currency_code = 'GBP';
UPDATE ref_country SET iso_numeric = '978', decimal_notation_value = 100  WHERE currency_code = 'EUR';
UPDATE ref_country SET iso_numeric = '682', decimal_notation_value = 100  WHERE currency_code = 'SAR';
UPDATE ref_country SET iso_numeric = '784', decimal_notation_value = 100  WHERE currency_code = 'AED';
UPDATE ref_country SET iso_numeric = '356', decimal_notation_value = 100  WHERE currency_code = 'INR';
UPDATE ref_country SET iso_numeric = '414', decimal_notation_value = 1000 WHERE currency_code = 'KWD';
UPDATE ref_country SET iso_numeric = '512', decimal_notation_value = 1000 WHERE currency_code = 'OMR';
UPDATE ref_country SET iso_numeric = '634', decimal_notation_value = 100  WHERE currency_code = 'QAR';
UPDATE ref_country SET iso_numeric = '392', decimal_notation_value = 1    WHERE currency_code = 'JPY';
UPDATE ref_country SET iso_numeric = '156', decimal_notation_value = 100  WHERE currency_code = 'CNY';
UPDATE ref_country SET iso_numeric = '036', decimal_notation_value = 100  WHERE currency_code = 'AUD';
UPDATE ref_country SET iso_numeric = '124', decimal_notation_value = 100  WHERE currency_code = 'CAD';
UPDATE ref_country SET iso_numeric = '756', decimal_notation_value = 100  WHERE currency_code = 'CHF';
UPDATE ref_country SET iso_numeric = '702', decimal_notation_value = 100  WHERE currency_code = 'SGD';
UPDATE ref_country SET iso_numeric = '410', decimal_notation_value = 1    WHERE currency_code = 'KRW';
UPDATE ref_country SET iso_numeric = '458', decimal_notation_value = 100  WHERE currency_code = 'MYR';

-- Done! You can now re-upload transaction files.
