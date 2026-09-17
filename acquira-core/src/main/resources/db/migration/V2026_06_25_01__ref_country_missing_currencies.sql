-- ============================================================
-- Migration: V2026_06_25_01__ref_country_missing_currencies
-- Purpose:   Insert 8 currency codes that were missing from
--            ref_country and triggered "currency not found"
--            warnings during transaction batch processing.
--
--            Missing codes identified from batch logs:
--              051 (AMD), 886 (YER), 901 (TWD), 946 (RON),
--              949 (TRY), 975 (BGN), 980 (UAH), 985 (PLN)
--
--            All have decimal_notation_value = 100 (2 decimal
--            places), matching ISO 4217 for each currency.
--
--            ON CONFLICT DO NOTHING is safe to run multiple
--            times — PK is country_code (alpha-2).
-- ============================================================

INSERT INTO ref_country
    (country_code, country_name, currency_code, currency_name, currency_symbol, phone_code, iso_numeric, decimal_notation_value)
VALUES
    ('AM', 'ARMENIA',       'AMD', 'Armenian Dram',      'AMD', '374',  '051', 100),
    ('YE', 'YEMEN',         'YER', 'Yemeni Rial',        'YER', '967',  '886', 100),
    ('TW', 'TAIWAN',        'TWD', 'New Taiwan Dollar',  'NT$', '886',  '901', 100),
    ('RO', 'ROMANIA',       'RON', 'Romanian Leu',       'lei', '40',   '946', 100),
    ('TR', 'TURKEY',        'TRY', 'Turkish Lira',       '₺',   '90',   '949', 100),
    ('BG', 'BULGARIA',      'BGN', 'Bulgarian Lev',      'лв',  '359',  '975', 100),
    ('UA', 'UKRAINE',       'UAH', 'Ukrainian Hryvnia',  '₴',   '380',  '980', 100),
    ('PL', 'POLAND',        'PLN', 'Polish Zloty',       'zł',  '48',   '985', 100)
ON CONFLICT (country_code) DO UPDATE
    SET iso_numeric            = EXCLUDED.iso_numeric,
        currency_code          = EXCLUDED.currency_code,
        currency_name          = EXCLUDED.currency_name,
        currency_symbol        = EXCLUDED.currency_symbol,
        decimal_notation_value = EXCLUDED.decimal_notation_value;
-- Note: ON CONFLICT DO UPDATE (rather than DO NOTHING) ensures that if a row
-- for the country already exists but was missing iso_numeric or decimal_notation_value,
-- those columns are backfilled. country_name and phone_code are left as-is
-- if the row already exists (safe — those columns are not in the SET clause
-- above to avoid overwriting any local customisations).
