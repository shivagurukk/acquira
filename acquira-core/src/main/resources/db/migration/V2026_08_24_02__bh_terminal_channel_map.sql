-- ============================================================================
-- V2026_08_24_02: BH terminal-type -> channel map (real processor values).
--
-- WHY
-- ---
-- Channel resolution (V2026_08_10_01) matches terminal_channel_map.raw_type
-- against UPPER(TRIM(dim_terminal.type)); BH had only two ASSUMPTION rows
-- ('POS'/'ECOM'), so every real BH terminal type resolved UNMAPPED_CHANNEL and
-- nothing priced. This seeds the full list supplied by the business
-- (2026-08-24) — 22 POS device/products + 6 e-commerce products.
--
-- DESIGN
-- ------
-- * Country-level (tenant_id NULL): these are the BH processor's product
--   values, shared by every BH tenant. A per-tenant override row (tenant_id
--   set) still wins if one tenant ever diverges.
-- * raw_type stored UPPERCASE — the resolver compares UPPER(TRIM(type)).
-- * 'NONE' is the literal feed token for typeless terminals -> POS (per list).
-- * 'STATIC QR' -> POS per the supplied list.
-- * DELIBERATELY NO '*' wildcard: an unlisted new product must surface as
--   UNMAPPED_CHANNEL (loud config gap) rather than silently pricing as POS —
--   same loud-over-wrong rule as the 2026-08-10 fee-resolution rework.
-- * The two 2026-08-10 ASSUMPTION rows ('POS','ECOM') are left in place; they
--   are correct as literal tokens and the unique index ignores duplicates here.
--
-- Idempotent via ON CONFLICT DO NOTHING against
-- uq_terminal_channel_map (country_code, COALESCE(tenant_id,0), raw_type).
-- Splitter-safe (no dollar-quoting).
-- ============================================================================

INSERT INTO terminal_channel_map (tenant_id, country_code, raw_type, channel, note)
SELECT v.* FROM ( VALUES
  -- ---- e-commerce products -------------------------------------------------
  (NULL::INT, 'BH', 'ECOM PROFILE',            'ECOM', 'BH feed 2026-08-24'),
  (NULL, 'BH', 'AFS ONE',                      'ECOM', 'BH feed 2026-08-24'),
  (NULL, 'BH', 'BENEFIT PG',                   'ECOM', 'BH feed 2026-08-24'),
  (NULL, 'BH', 'MPGS',                         'ECOM', 'BH feed 2026-08-24'),
  (NULL, 'BH', 'PAY ON',                       'ECOM', 'BH feed 2026-08-24'),
  (NULL, 'BH', 'PAY BY LINK',                  'ECOM', 'BH feed 2026-08-24'),
  -- ---- POS devices / products ---------------------------------------------
  (NULL, 'BH', 'NONE',                         'POS',  'BH feed 2026-08-24 (literal token)'),
  (NULL, 'BH', 'DEFAULT POS',                  'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'AISINO A80',                   'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'AISINO A90',                   'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'AISINO V50',                   'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'PAX A50',                      'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'PAX A60',                      'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'PAX A910',                     'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'PAX A920',                     'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'PAX A920 PRO',                 'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'PAX IM30',                     'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'NEWLAND N950',                 'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'NEWLAND 950S',                 'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'N96 (NORMAL POS DEVICE)',      'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'E700',                         'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'E800',                         'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'S900',                         'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'VERIFONE X990',                'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'SOFTPOS',                      'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'MOBILETERMINAL',               'POS',  'BH feed 2026-08-24'),
  (NULL, 'BH', 'STATIC QR',                    'POS',  'BH feed 2026-08-24 (per business list)'),
  (NULL, 'BH', 'PLANET POS',                   'POS',  'BH feed 2026-08-24')
) AS v(tenant_id, country_code, raw_type, channel, note)
ON CONFLICT (country_code, COALESCE(tenant_id, 0), raw_type) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_24_02__bh_terminal_channel_map.sql') ON CONFLICT (filename) DO NOTHING;
