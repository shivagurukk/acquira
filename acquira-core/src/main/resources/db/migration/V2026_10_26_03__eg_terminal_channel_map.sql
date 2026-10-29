-- ============================================================================
-- V2026_10_26_03: Egypt terminal type -> channel map, from the EG processor
--                 feed (user-supplied list 2026-10-26).
--
-- WHY: every EG transaction resolved fee_resolution_status = UNMAPPED_CHANNEL
-- (25,072 of 25,075 rows on 15-20 Oct 2026), so interchange AND scheme fee
-- were stored NULL and the dashboards showed 0.00. The V2026_08_10_01 EG seed
-- only knew POS / ECOM / ECOM PROFILE / MPGS / PAY BY LINK as assumptions and
-- the real feed carries device model names.
--
-- The engine matches raw_type = UPPER(TRIM(dim_terminal.type)), so values are
-- stored upper-cased. 'None' is mapped both as the literal token and as the
-- empty string (same treatment as BH V2026_08_26_01). No '*' wildcard is
-- added on purpose: a new, unlisted device type must surface as
-- UNMAPPED_CHANNEL rather than silently price as POS.
--
-- Idempotent (NOT EXISTS per row), splitter-safe. After applying, re-price
-- the affected EG months (fees compute at ingest).
-- ============================================================================
INSERT INTO terminal_channel_map (tenant_id, country_code, raw_type, channel, note)
SELECT NULL, 'EG', v.raw_type, v.channel, 'EG processor feed terminal type, confirmed 2026-10-26'
FROM (VALUES
  ('NONE',                               'POS'),
  ('',                                   'POS'),
  ('AISINO A90',                         'POS'),
  ('AISINO V50',                         'POS'),
  ('AISINO A80',                         'POS'),
  ('AISINO A75',                         'POS'),
  ('PAX A920',                           'POS'),
  ('PAX A920 PRO',                       'POS'),
  ('PAX A910',                           'POS'),
  ('PAX A60',                            'POS'),
  ('PAX A50',                            'POS'),
  ('SOFTPOS',                            'POS'),
  ('AFS ONE',                            'POS'),
  ('N62 (MINI DEVICE WITH NO PRINTER)',  'POS'),
  ('N96 (NORMAL POS DEVICE)',            'POS'),
  ('PLANET POS',                         'POS'),
  ('ECOM PROFILE',                       'ECOM'),
  ('PAY ON',                             'ECOM'),
  ('PAY BY LINK',                        'ECOM')
) AS v(raw_type, channel)
WHERE NOT EXISTS (
  SELECT 1 FROM terminal_channel_map x
  WHERE x.country_code = 'EG' AND x.tenant_id IS NULL AND x.raw_type = v.raw_type
);

ANALYZE terminal_channel_map;

-- Verify: every listed type should show a channel.
SELECT raw_type, channel FROM terminal_channel_map
WHERE country_code = 'EG' AND tenant_id IS NULL ORDER BY channel, raw_type;

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_26_03__eg_terminal_channel_map.sql')
ON CONFLICT (filename) DO NOTHING;
