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
