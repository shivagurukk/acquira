-- ============================================================================
-- V2026_08_20_03: seed the UAE tenant BIN -> bank list (UAESWITCH POS BIN list,
--   file date 2026-07-30 — 158 BINs across 40 banks).
--
-- Feeds the Local Debit Bank Dashboard (/business/local-debit-bank-dashboard).
-- Bank names are resolved at QUERY time by joining ref_tenant_bin_bank, so
-- seeding here re-labels ALL history immediately — no summary rebuild needed.
-- A local-debit BIN absent from this table renders in the "Other Banks" bucket;
-- it is never dropped, so totals stay correct either way.
--
-- TENANT RESOLUTION
-- -----------------
-- A migration cannot take a -v tenant_id parameter, so the rows are attached to
-- every tenant whose home_country_code = 'AE'. These are UAE issuer BINs, so
-- that is the correct scope; a non-UAE tenant gets nothing. Adding a new UAE
-- tenant later picks the list up automatically on the next startup.
--
-- ON CONFLICT DO NOTHING — DELIBERATE, DO NOT CHANGE TO DO UPDATE
-- ---------------------------------------------------------------
-- This script re-runs on EVERY startup. With DO UPDATE it would silently revert
-- any correction a DBA had made to a bank name (via
-- docs/deploy/04_add_bins_template.sql) the next time the service restarted.
-- DO NOTHING makes this an initial seed only: existing rows are left alone, and
-- genuinely new BINs still get added.
--
-- Splitter-safe: no dollar-quoted blocks (Spring's sql.init splitter cuts on
-- semicolons and mangles them), and the whole seed is ONE statement. Registered in
-- spring.sql.init.schema-locations AFTER V2026_08_20_01, which creates the
-- table. On prod (sql.init.mode=never) apply once via psql.
--
-- Tracked copy of this file, plus the tenant-parameterised variant and the
-- add/correct template: docs/deploy/ (db/migration/ is gitignored).
-- ============================================================================

INSERT INTO ref_tenant_bin_bank (tenant_id, bin, bank_name, source_file)
SELECT t.tenant_id, v.bin, v.bank_name, 'UAESWITCH-POS-BIN-20260730'
FROM tenant t
CROSS JOIN (VALUES
    ('476578','AAIB'),
    ('471388','Abu Dhabi Commercial Bank'),
    ('471389','Abu Dhabi Commercial Bank'),
    ('471390','Abu Dhabi Commercial Bank'),
    ('526408','Abu Dhabi Commercial Bank'),
    ('529192','Abu Dhabi Commercial Bank'),
    ('529342','Abu Dhabi Commercial Bank'),
    ('532660','Abu Dhabi Commercial Bank'),
    ('532665','Abu Dhabi Commercial Bank'),
    ('542018','Abu Dhabi Commercial Bank'),
    ('554162','Abu Dhabi Commercial Bank'),
    ('557652','Abu Dhabi Commercial Bank'),
    ('669087','Abu Dhabi Commercial Bank'),
    ('425893','Abu Dhabi Islamic Bank'),
    ('433367','Abu Dhabi Islamic Bank'),
    ('471366','Abu Dhabi Islamic Bank'),
    ('471367','Abu Dhabi Islamic Bank'),
    ('471368','Abu Dhabi Islamic Bank'),
    ('472455','Abu Dhabi Islamic Bank'),
    ('669009','Abu Dhabi Islamic Bank'),
    ('524455','AJMAN Bank PJSC'),
    ('524883','AJMAN Bank PJSC'),
    ('532033','AJMAN Bank PJSC'),
    ('541824','AJMAN Bank PJSC'),
    ('429790','AL AHLI BANK OF KUWAIT'),
    ('419236','Al Hilal'),
    ('471484','Al Hilal'),
    ('516171','Al Hilal'),
    ('539462','Al Hilal'),
    ('458464','AL Hilal Bank PJSC'),
    ('539436','AL Hilal Bank PJSC'),
    ('521088','Al Khaliji France S.A'),
    ('519212','Al Maryah Community Bank'),
    ('546771','Al Maryah Community Bank'),
    ('553080','Al Maryah Community Bank'),
    ('539193','Al Masraf'),
    ('549173','Al Masraf'),
    ('447521','Arab Bank'),
    ('450707','Arab Bank'),
    ('468584','BANK OF BARODA'),
    ('468585','BANK OF BARODA'),
    ('519616','Bank Of Sharjah'),
    ('415416','Banque MISR'),
    ('532712','BLOM Bank France'),
    ('555941','BLOM Bank France'),
    ('412620','CBD'),
    ('440885','CBD'),
    ('514469','CITI'),
    ('546529','CITI'),
    ('557587','CITI'),
    ('539980','Commercial Bank International'),
    ('444463','Doha Bank'),
    ('423117','Dubai Islamic Bank'),
    ('456835','Dubai Islamic Bank'),
    ('457826','Dubai Islamic Bank'),
    ('459042','Dubai Islamic Bank'),
    ('480626','Emirates Islamic'),
    ('480645','Emirates Islamic'),
    ('480666','Emirates Islamic'),
    ('480668','Emirates Islamic'),
    ('529510','Emirates Islamic'),
    ('400062','Emirates NBD PJSC'),
    ('443911','Emirates NBD PJSC'),
    ('443913','Emirates NBD PJSC'),
    ('461781','Emirates NBD PJSC'),
    ('498778','Emirates NBD PJSC'),
    ('669071','Emirates NBD PJSC'),
    ('432238','First Abu Dhabi Bank PJSC'),
    ('440997','First Abu Dhabi Bank PJSC'),
    ('445692','First Abu Dhabi Bank PJSC'),
    ('455081','First Abu Dhabi Bank PJSC'),
    ('458860','First Abu Dhabi Bank PJSC'),
    ('458861','First Abu Dhabi Bank PJSC'),
    ('458862','First Abu Dhabi Bank PJSC'),
    ('492096','First Abu Dhabi Bank PJSC'),
    ('493749','First Abu Dhabi Bank PJSC'),
    ('517554','First Abu Dhabi Bank PJSC'),
    ('533113','First Abu Dhabi Bank PJSC'),
    ('533191','First Abu Dhabi Bank PJSC'),
    ('535987','First Abu Dhabi Bank PJSC'),
    ('535996','First Abu Dhabi Bank PJSC'),
    ('539987','First Abu Dhabi Bank PJSC'),
    ('557661','First Abu Dhabi Bank PJSC'),
    ('424050','Habib Bank AG Zurich'),
    ('433958','Habib Bank AG Zurich'),
    ('446621','Habib Bank AG Zurich'),
    ('450604','Habib Bank AG Zurich'),
    ('471448','Habib Bank AG Zurich'),
    ('405042','HBL'),
    ('405048','HBL'),
    ('405069','HBL'),
    ('419647','HSBC Bank Middle East'),
    ('419648','HSBC Bank Middle East'),
    ('419649','HSBC Bank Middle East'),
    ('424078','HSBC Bank Middle East'),
    ('428687','HSBC Bank Middle East'),
    ('428688','HSBC Bank Middle East'),
    ('428689','HSBC Bank Middle East'),
    ('439052','HSBC Bank Middle East'),
    ('446623','HSBC Bank Middle East'),
    ('465821','HSBC Bank Middle East'),
    ('511851','Invest Bank'),
    ('417125','JANATA BANK'),
    ('403410','Mashreq Bank'),
    ('408565','Mashreq Bank'),
    ('412347','Mashreq Bank'),
    ('419797','Mashreq Bank'),
    ('421536','Mashreq Bank'),
    ('421908','Mashreq Bank'),
    ('427299','Mashreq Bank'),
    ('428324','Mashreq Bank'),
    ('478747','Mashreq Bank'),
    ('478780','Mashreq Bank'),
    ('494111','Mashreq Bank'),
    ('622454','Mercury'),
    ('650053','Mercury'),
    ('650445','Mercury'),
    ('650447','Mercury'),
    ('650483','Mercury'),
    ('430255','National Bank Of Fujairah'),
    ('430287','National Bank Of Fujairah'),
    ('464123','National Bank Of Fujairah'),
    ('470330','National Bank Of Fujairah'),
    ('418767','National Bank of Kuwait'),
    ('479734','National Bank of Umm Al Qaiwan'),
    ('511879','RAKBANK'),
    ('521070','RAKBANK'),
    ('522707','RAKBANK'),
    ('522968','RAKBANK'),
    ('528428','RAKBANK'),
    ('529546','RAKBANK'),
    ('529549','RAKBANK'),
    ('533605','RAKBANK'),
    ('535021','RAKBANK'),
    ('530280','Ruya Community Islamic Bank'),
    ('531752','Ruya Community Islamic Bank'),
    ('544923','Ruya Community Islamic Bank'),
    ('457928','SAMBA'),
    ('458465','SAMBA'),
    ('512970','Sharjah Islamic Bank'),
    ('525572','Sharjah Islamic Bank'),
    ('527385','Sharjah Islamic Bank'),
    ('527639','Sharjah Islamic Bank'),
    ('532425','Sharjah Islamic Bank'),
    ('532723','Sharjah Islamic Bank'),
    ('533539','Sharjah Islamic Bank'),
    ('403397','Standard Chartered Bank'),
    ('407804','Standard Chartered Bank'),
    ('409381','Standard Chartered Bank'),
    ('449197','Standard Chartered Bank'),
    ('458562','Standard Chartered Bank'),
    ('458575','Standard Chartered Bank'),
    ('458510','United Arab Bank'),
    ('544371','United Arab Bank'),
    ('552119','United Arab Bank'),
    ('669010','United Arab Bank'),
    ('417894','United Bank Limited'),
    ('428226','United Bank Limited')
) AS v(bin, bank_name)
WHERE t.home_country_code = 'AE'
ON CONFLICT (tenant_id, bin) DO NOTHING;
