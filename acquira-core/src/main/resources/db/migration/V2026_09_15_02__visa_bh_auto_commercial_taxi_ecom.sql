-- V2026_09_15_02: BH Visa sector exactness follow-up to V2026_09_15_01,
-- per the Visa Domestic IRF Guide (Bahrain, 25 Jan 2025) section 6.1.4:
--
--   A) Auto Dealers (5511/5521) is "Consumer Products only, capped USD 150".
--      A COMMERCIAL Visa card at an auto dealer does not qualify for the
--      industry program and must fall to its uncapped product rate.
--      -> tier-specific commercial rows at priority 61 (beat the capped
--         consumer row at 60; the tier match keeps consumer BINs out, and
--         an unmapped BIN still takes the capped 60 row).
--
--   B) Taxi 0.60% (4121) carries additional edit criteria: POS entry modes
--      05/07/90/91 (card-present). An e-commerce taxi transaction fails the
--      program and prices at the Alternative product rate.
--      -> delete the Visa ECOM 4121 row at 0.60 and add tier rows at
--         priority 61 with the Alternative rates (the any-scheme 4121 ECOM
--         row at priority 40 would otherwise shadow the tier ladder at 20).
--
-- Guarded on schema_migration_log per the ledger convention.

-- A) Commercial tiers at auto dealers: product rate (POS) / alternative (ECOM), no cap
INSERT INTO interchange_rate_local (tenant_id,priority,dest,channel,scheme_group,card_type,tier,mcc_sector,interchange_pct,cap_amount,label,mcc,country_code,cap_currency_code,flat_fee,rate_status,effective_from,effective_to,source_note,min_ticket,max_ticket)
SELECT NULL,61,'DOMESTIC',v.channel,'Visa',NULL,v.tier,NULL,v.pct,NULL,
       'BH Visa '||v.tier||' '||v.channel||' auto '||v.mcc||' uncapped (commercial exempt from USD150 program)',
       v.mcc,'BH','BHD',0.0000,'APPROVED',NULL,NULL,'Visa BH IRF Guide 2025-01-25 s6.1.4 Auto Dealers consumer-only',NULL,NULL
FROM (VALUES
  ('POS','VBUS',0.020000,'5511'),   ('ECOM','VBUS',0.022000,'5511'),
  ('POS','VBUS',0.020000,'5521'),   ('ECOM','VBUS',0.022000,'5521'),
  ('POS','VCORP',0.020000,'5511'),  ('ECOM','VCORP',0.022000,'5511'),
  ('POS','VCORP',0.020000,'5521'),  ('ECOM','VCORP',0.022000,'5521'),
  ('POS','VPUR',0.020000,'5511'),   ('ECOM','VPUR',0.022000,'5511'),
  ('POS','VPUR',0.020000,'5521'),   ('ECOM','VPUR',0.022000,'5521'),
  ('POS','VBUSSIG',0.021000,'5511'),('ECOM','VBUSSIG',0.023000,'5511'),
  ('POS','VBUSSIG',0.021000,'5521'),('ECOM','VBUSSIG',0.023000,'5521'),
  ('POS','VBUSINF',0.021500,'5511'),('ECOM','VBUSINF',0.023500,'5511'),
  ('POS','VBUSINF',0.021500,'5521'),('ECOM','VBUSINF',0.023500,'5521')
) AS v(channel,tier,pct,mcc)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename='V2026_09_15_02__visa_bh_auto_commercial_taxi_ecom.sql');

-- B1) Taxi 0.60 is card-present only: remove the Visa ECOM 4121 row
DELETE FROM interchange_rate_local
WHERE country_code='BH' AND scheme_group='Visa' AND mcc='4121'
  AND channel='ECOM' AND interchange_pct=0.006000
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename='V2026_09_15_02__visa_bh_auto_commercial_taxi_ecom.sql');

-- B2) ECOM taxi prices at the Alternative product rate per tier
INSERT INTO interchange_rate_local (tenant_id,priority,dest,channel,scheme_group,card_type,tier,mcc_sector,interchange_pct,cap_amount,label,mcc,country_code,cap_currency_code,flat_fee,rate_status,effective_from,effective_to,source_note,min_ticket,max_ticket)
SELECT NULL,61,'DOMESTIC','ECOM','Visa',NULL,v.tier,NULL,v.pct,NULL,
       'BH Visa '||v.tier||' ECOM taxi 4121 alternative rate (0.60 is card-present only)',
       '4121','BH','BHD',0.0000,'APPROVED',NULL,NULL,'Visa BH IRF Guide 2025-01-25 s6.1.4 Taxi entry modes 05/07/90/91',NULL,NULL
FROM (VALUES
  ('VCLAS',0.014500), ('VGOLD',0.016000), ('VRWD',0.019000), ('VPLAT',0.020500),
  ('VSIG',0.022500),  ('VINF',0.023000),  ('VUHNW',0.025000),
  ('VBUS',0.022000),  ('VCORP',0.022000), ('VPUR',0.022000),
  ('VBUSSIG',0.023000),('VBUSINF',0.023500)
) AS v(tier,pct)
WHERE NOT EXISTS (SELECT 1 FROM schema_migration_log WHERE filename='V2026_09_15_02__visa_bh_auto_commercial_taxi_ecom.sql');

-- Verify: 20 commercial auto rows, 12 taxi ECOM tier rows, no Visa ECOM 4121 at 0.60
SELECT 'auto_commercial_rows' AS check, COUNT(*) FROM interchange_rate_local
WHERE country_code='BH' AND scheme_group='Visa' AND priority=61 AND mcc IN ('5511','5521')
UNION ALL
SELECT 'taxi_ecom_tier_rows', COUNT(*) FROM interchange_rate_local
WHERE country_code='BH' AND scheme_group='Visa' AND priority=61 AND mcc='4121'
UNION ALL
SELECT 'taxi_ecom_060_left', COUNT(*) FROM interchange_rate_local
WHERE country_code='BH' AND scheme_group='Visa' AND mcc='4121' AND channel='ECOM' AND interchange_pct=0.006000;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_15_02__visa_bh_auto_commercial_taxi_ecom.sql') ON CONFLICT (filename) DO NOTHING;
