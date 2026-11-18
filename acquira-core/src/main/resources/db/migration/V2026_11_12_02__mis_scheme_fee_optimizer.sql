-- ============================================================================
-- V2026_11_12_02: MIS / Scheme Fee Optimizer — read the scheme's invoice,
-- classify every line, and flag what should not be there.
--
-- WHAT IT IS: the scheme bills the acquirer monthly, in a file of hundreds of
-- fee lines keyed by billing event code. Nobody reads it line by line, so
-- duplicate charges, rate drift, fixed fees billed at zero quantity and
-- penalties sit in it unchallenged. This module parses the invoice, maps each
-- line to a category, and raises findings that can be tracked from flag to
-- recovery.
--
-- It is the enforcement half of the pair whose reference half already exists:
-- /ops/scheme-billing-reference holds the published fee specifications; this
-- holds what was actually charged. The rule that reference data is never
-- self-service still stands — an invoice NEVER writes to a rate card. It is
-- evidence for a dispute, not a source of pricing.
--
-- ---------------------------------------------------------------------------
-- WHY THERE IS NO SEEDED EVENT-CODE LIST
-- ---------------------------------------------------------------------------
-- A dictionary of Visa and Mastercard billing event codes could only be
-- correct if it were transcribed from the published billing manuals. Inventing
-- plausible codes would produce a module that looks authoritative and
-- classifies real invoices wrongly — the worst of both. So classification
-- starts from DESCRIPTION PATTERNS (sfo_classification_pattern, seeded below),
-- which are honest about being heuristics and carry a confidence; the first
-- time a code is seen it is learned into sfo_event_dictionary with that
-- category and confidence, where it can then be corrected once and stay
-- corrected. A per-tenant override outranks both.
--
-- Resolution order, most specific first:
--   1. sfo_classification_override  — this tenant said so
--   2. sfo_event_dictionary         — this code has been seen and settled
--   3. sfo_classification_pattern   — the description looks like this
--   4. OTHER, confidence 0          — say so rather than guess
--
-- Idempotent; splitter-safe (no dollar-quoting). On prod apply once via psql.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Billing entities
--
-- One tenant can be billed under several ICAs / BIDs. The invoice names the
-- entity, so findings and trends can be cut by it.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_entity (
    entity_id    BIGSERIAL PRIMARY KEY,
    tenant_id    INT NOT NULL,
    entity_code  VARCHAR(40)  NOT NULL,   -- as it appears on the invoice
    entity_name  VARCHAR(160) NOT NULL,
    scheme       VARCHAR(20),             -- VISA / MASTERCARD / NULL(both)
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, entity_code)
);
ALTER TABLE sfo_entity ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sfo_entity;
CREATE POLICY tenant_isolation_policy ON sfo_entity USING (tenant_id = get_current_tenant());

-- ---------------------------------------------------------------------------
-- 2. Indicative FX
--
-- Invoices arrive in local currency; the dashboard compares across countries,
-- so every line also carries a USD amount. These are INDICATIVE pegs, not the
-- bank's dealt rates — the module says so on screen. A finding's value is
-- always quoted in the invoice's own currency as well, so a dispute is never
-- argued off a converted number.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_fx_rate (
    currency_code VARCHAR(3) PRIMARY KEY,
    usd_rate      DECIMAL(18, 8) NOT NULL,
    note          VARCHAR(80)
);

INSERT INTO sfo_fx_rate (currency_code, usd_rate, note)
SELECT v.* FROM (VALUES
    ('USD', 1.00000000, 'Base'),
    ('AED', 0.27247956, 'Pegged 3.6725'),
    ('BHD', 2.65000000, 'Pegged 0.376'),
    ('SAR', 0.26666667, 'Pegged 3.75'),
    ('QAR', 0.27472527, 'Pegged 3.64'),
    ('OMR', 2.60000000, 'Pegged 0.3845'),
    ('KWD', 3.26000000, 'Managed basket — indicative'),
    ('EGP', 0.02060000, 'Floating — indicative only'),
    ('EUR', 1.08000000, 'Floating — indicative only'),
    ('GBP', 1.27000000, 'Floating — indicative only')
) AS v(currency_code, usd_rate, note)
WHERE NOT EXISTS (SELECT 1 FROM sfo_fx_rate f WHERE f.currency_code = v.currency_code);

-- ---------------------------------------------------------------------------
-- 3. Uploads — the intake pipeline
--
-- status: QUEUED -> PARSING -> NEEDS_REVIEW -> DONE, or FAILED.
-- NEEDS_REVIEW is not an error. It is the confirm step: the parser says what
-- it detected and what it could not, and a human agrees before a single line
-- is counted. Nothing reaches sfo_fee_line until the upload is confirmed.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_upload (
    upload_id       BIGSERIAL PRIMARY KEY,
    tenant_id       INT NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    file_size       BIGINT,
    content_hash    VARCHAR(64),           -- SHA-256, to spot a re-upload
    scheme          VARCHAR(20),
    entity_code     VARCHAR(40),
    period_year     INT,
    period_month    INT,
    invoice_ref     VARCHAR(60),
    currency_code   VARCHAR(3),
    invoice_total   DECIMAL(19, 4),        -- as printed on the invoice
    parsed_total    DECIMAL(19, 4),        -- sum of the lines we read
    line_count      INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    detection       TEXT,                  -- JSON: what was detected and how
    staged_lines    TEXT,                  -- JSON: parsed rows awaiting confirm
    error_message   VARCHAR(1000),
    uploaded_by     VARCHAR(100),
    uploaded_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_by    VARCHAR(100),
    confirmed_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sfo_upload_tenant ON sfo_upload (tenant_id, uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_sfo_upload_period ON sfo_upload (tenant_id, period_year, period_month);
ALTER TABLE sfo_upload ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sfo_upload;
CREATE POLICY tenant_isolation_policy ON sfo_upload USING (tenant_id = get_current_tenant());

-- ---------------------------------------------------------------------------
-- 4. Fee lines — the raw ledger
--
-- One row per line of one invoice, linked to the upload it came from so every
-- figure on every screen traces back to a file somebody can open.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_fee_line (
    line_id         BIGSERIAL PRIMARY KEY,
    tenant_id       INT NOT NULL,
    upload_id       BIGINT NOT NULL REFERENCES sfo_upload(upload_id) ON DELETE CASCADE,
    period_year     INT NOT NULL,
    period_month    INT NOT NULL,
    scheme          VARCHAR(20) NOT NULL,
    entity_code     VARCHAR(40),
    event_code      VARCHAR(60),
    description     VARCHAR(400),
    quantity        DECIMAL(19, 4),
    unit_rate       DECIMAL(19, 8),
    amount          DECIMAL(19, 4) NOT NULL,
    currency_code   VARCHAR(3) NOT NULL,
    amount_usd      DECIMAL(19, 4) NOT NULL,
    category        VARCHAR(30) NOT NULL DEFAULT 'OTHER',
    confidence      DECIMAL(5, 2) NOT NULL DEFAULT 0,   -- 0..100
    classified_by   VARCHAR(20) NOT NULL DEFAULT 'PATTERN', -- OVERRIDE/DICTIONARY/PATTERN/NONE
    source_row      INT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sfo_line_tenant_period
    ON sfo_fee_line (tenant_id, period_year, period_month);
CREATE INDEX IF NOT EXISTS idx_sfo_line_event   ON sfo_fee_line (tenant_id, scheme, event_code);
CREATE INDEX IF NOT EXISTS idx_sfo_line_upload  ON sfo_fee_line (upload_id);
CREATE INDEX IF NOT EXISTS idx_sfo_line_category ON sfo_fee_line (tenant_id, category);
ALTER TABLE sfo_fee_line ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sfo_fee_line;
CREATE POLICY tenant_isolation_policy ON sfo_fee_line USING (tenant_id = get_current_tenant());

-- ---------------------------------------------------------------------------
-- 5. Event dictionary — codes that have been seen and settled
--
-- tenant_id NULL = a globally known code. A learned code is written with the
-- pattern's category and confidence; correcting it once makes every future
-- invoice classify correctly.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_event_dictionary (
    dict_id       BIGSERIAL PRIMARY KEY,
    tenant_id     INT,
    scheme        VARCHAR(20) NOT NULL,
    event_code    VARCHAR(60) NOT NULL,
    description   VARCHAR(400),
    category      VARCHAR(30) NOT NULL,
    explanation   VARCHAR(1000),
    confidence    DECIMAL(5, 2) NOT NULL DEFAULT 50,
    learned       BOOLEAN NOT NULL DEFAULT TRUE,   -- FALSE once a human edits it
    first_seen    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(100)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sfo_dict_global
    ON sfo_event_dictionary (scheme, event_code) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_sfo_dict_tenant
    ON sfo_event_dictionary (tenant_id, scheme, event_code) WHERE tenant_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 6. Per-tenant classification overrides — the last word
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_classification_override (
    override_id  BIGSERIAL PRIMARY KEY,
    tenant_id    INT NOT NULL,
    scheme       VARCHAR(20) NOT NULL,
    event_code   VARCHAR(60) NOT NULL,
    category     VARCHAR(30) NOT NULL,
    note         VARCHAR(400),
    created_by   VARCHAR(100),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, scheme, event_code)
);
ALTER TABLE sfo_classification_override ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sfo_classification_override;
CREATE POLICY tenant_isolation_policy ON sfo_classification_override
    USING (tenant_id = get_current_tenant());

-- ---------------------------------------------------------------------------
-- 7. Description patterns — the day-one classifier
--
-- Matched case-insensitively against the line description, highest priority
-- first. Confidence is deliberately below 100: a keyword match is a strong
-- hint, not a reading of the billing manual.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_classification_pattern (
    pattern_id  BIGSERIAL PRIMARY KEY,
    scheme      VARCHAR(20),            -- NULL = both schemes
    match_text  VARCHAR(80) NOT NULL,   -- substring, lower-cased
    category    VARCHAR(30) NOT NULL,
    confidence  DECIMAL(5, 2) NOT NULL DEFAULT 60,
    priority    INT NOT NULL DEFAULT 0,
    UNIQUE (scheme, match_text)
);

INSERT INTO sfo_classification_pattern (scheme, match_text, category, confidence, priority)
SELECT v.* FROM (VALUES
    -- Penalties first: a penalty line usually also mentions the activity that
    -- triggered it, so a weaker category must not win the match.
    (NULL,'penalt','PENALTIES',85,100),
    (NULL,'fine','PENALTIES',75,100),
    (NULL,'non-compliance','PENALTIES',85,100),
    (NULL,'noncompliance','PENALTIES',85,100),
    (NULL,'violation','PENALTIES',85,100),
    (NULL,'excessive','PENALTIES',70,100),
    (NULL,'late submission','PENALTIES',85,100),
    (NULL,'late presentment','PENALTIES',85,100),
    (NULL,'integrity fee','PENALTIES',70,95),
    (NULL,'misuse','PENALTIES',75,95),
    -- Cross-border and currency.
    (NULL,'cross border','CROSS_BORDER',90,80),
    (NULL,'cross-border','CROSS_BORDER',90,80),
    (NULL,'crossborder','CROSS_BORDER',85,80),
    (NULL,'international','CROSS_BORDER',70,75),
    (NULL,'currency conversion','CROSS_BORDER',85,80),
    (NULL,'nnss','CROSS_BORDER',70,75),
    (NULL,'issuer country','CROSS_BORDER',70,75),
    -- Authorisation.
    (NULL,'authoriz','AUTHORISATION',85,70),
    (NULL,'authoris','AUTHORISATION',85,70),
    (NULL,'auth ','AUTHORISATION',70,65),
    (NULL,'pre-auth','AUTHORISATION',80,70),
    (NULL,'stand-in','AUTHORISATION',80,70),
    (NULL,'stip','AUTHORISATION',70,65),
    (NULL,'account status inquiry','AUTHORISATION',80,70),
    -- Clearing and settlement processing.
    (NULL,'clearing','CLEARING',85,60),
    (NULL,'settlement','CLEARING',75,60),
    (NULL,'transaction processing','CLEARING',80,60),
    (NULL,'presentment','CLEARING',75,60),
    (NULL,'chargeback','CLEARING',70,60),
    (NULL,'retrieval','CLEARING',70,60),
    (NULL,'file transmission','CLEARING',65,55),
    -- Assessments and volume-based service fees.
    (NULL,'assessment','ASSESSMENTS',90,50),
    (NULL,'service fee','ASSESSMENTS',70,50),
    (NULL,'volume fee','ASSESSMENTS',75,50),
    (NULL,'brand usage','ASSESSMENTS',80,50),
    (NULL,'acquirer brand','ASSESSMENTS',80,50),
    -- Fixed and recurring charges.
    (NULL,'minimum','FIXED_FEES',75,40),
    (NULL,'licen','FIXED_FEES',75,40),
    (NULL,'membership','FIXED_FEES',80,40),
    (NULL,'annual fee','FIXED_FEES',80,40),
    (NULL,'monthly fee','FIXED_FEES',80,40),
    (NULL,'connectivity','FIXED_FEES',75,40),
    (NULL,'endpoint','FIXED_FEES',70,40),
    (NULL,'network access','FIXED_FEES',75,40),
    (NULL,'registration','FIXED_FEES',70,40),
    (NULL,'subscription','FIXED_FEES',75,40)
) AS v(scheme, match_text, category, confidence, priority)
WHERE NOT EXISTS (
    SELECT 1 FROM sfo_classification_pattern p
     WHERE p.scheme IS NOT DISTINCT FROM v.scheme AND p.match_text = v.match_text);

-- ---------------------------------------------------------------------------
-- 8. Savings centre — findings, from flag to recovery
--
-- A finding is keyed by a fingerprint so that re-running detection updates the
-- existing row rather than raising the same thing twice. The workflow state a
-- human has set is never overwritten by a re-run: only the value and the
-- evidence are refreshed.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_opportunity (
    opportunity_id   BIGSERIAL PRIMARY KEY,
    tenant_id        INT NOT NULL,
    fingerprint      VARCHAR(120) NOT NULL,
    finding_type     VARCHAR(30) NOT NULL,
    severity         VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',  -- HIGH/MEDIUM/LOW
    scheme           VARCHAR(20),
    entity_code      VARCHAR(40),
    event_code       VARCHAR(60),
    description      VARCHAR(400),
    period_year      INT,
    period_month     INT,
    currency_code    VARCHAR(3),
    monthly_value    DECIMAL(19, 4) NOT NULL DEFAULT 0,
    annualised_value DECIMAL(19, 4) NOT NULL DEFAULT 0,
    monthly_value_usd DECIMAL(19, 4) NOT NULL DEFAULT 0,
    annualised_value_usd DECIMAL(19, 4) NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL DEFAULT 'FLAGGED',
    evidence         TEXT,                 -- JSON: the lines behind the finding
    note             VARCHAR(1000),
    detected_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by       VARCHAR(100),
    UNIQUE (tenant_id, fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_sfo_opp_tenant ON sfo_opportunity (tenant_id, status, detected_at DESC);
ALTER TABLE sfo_opportunity ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sfo_opportunity;
CREATE POLICY tenant_isolation_policy ON sfo_opportunity USING (tenant_id = get_current_tenant());

-- ---------------------------------------------------------------------------
-- 9. Menu
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Scheme Fee Optimizer', '/mis/fee-optimizer', 'Receipt', 'MIS', 2
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/mis/fee-optimizer');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/mis/fee-optimizer'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'Finance User')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/mis/fee-optimizer'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_11_12_02__mis_scheme_fee_optimizer.sql') ON CONFLICT (filename) DO NOTHING;
