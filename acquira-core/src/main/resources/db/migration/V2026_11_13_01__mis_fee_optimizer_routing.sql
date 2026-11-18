-- ============================================================================
-- V2026_11_13_01: MIS / Scheme Fee Optimizer — route findings to the team that
-- can actually fix them.
--
-- WHY: a finding with no owner is a finding nobody works. The savings centre
-- shipped as a list; this turns it into a queue per team, with a due date.
--
-- The classification axis is deliberately NOT "whose budget pays the fee" but
-- "who can change the thing that caused it". An authorisation integrity
-- penalty routed to Finance dies in a spreadsheet; routed to the
-- authorisations team it gets fixed at the switch and stops recurring. That
-- is the whole point of routing it.
--
-- ---------------------------------------------------------------------------
-- WHAT
--   sfo_team          the teams a finding can belong to, with a default SLA
--   sfo_routing_rule  finding shape -> team + SLA. DATA, not Java, so the
--                     mapping changes without a deploy
--   sfo_opportunity   + category, team_code, assignee, due_date, routed_at,
--                     routed_by
--
-- RESOLUTION: highest priority wins; within a priority, the rule that names
-- the most dimensions. A rule leaving a dimension NULL matches anything there.
-- There is a catch-all at priority 0 so a finding is never silently orphaned.
--
-- A HAND-ASSIGNED finding (routed_by = 'MANUAL') is never re-routed by a
-- re-scan. Somebody chose that owner; a nightly job must not quietly undo it.
--
-- Idempotent; splitter-safe (no dollar-quoting). On prod apply once via psql.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Teams
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_team (
    team_id          BIGSERIAL PRIMARY KEY,
    tenant_id        INT NOT NULL,
    team_code        VARCHAR(40)  NOT NULL,
    team_name        VARCHAR(120) NOT NULL,
    description      VARCHAR(400),
    -- Days from detection to the due date, unless a rule overrides it.
    default_sla_days INT NOT NULL DEFAULT 14,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    display_order    INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, team_code)
);
ALTER TABLE sfo_team ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sfo_team;
CREATE POLICY tenant_isolation_policy ON sfo_team USING (tenant_id = get_current_tenant());

-- Seeded for every tenant. These are starting names, not a fixed taxonomy —
-- an institution renames them to match its own org chart.
INSERT INTO sfo_team (tenant_id, team_code, team_name, description, default_sla_days, display_order)
SELECT t.tenant_id, v.team_code, v.team_name, v.description, v.sla, v.ord
FROM tenant t
CROSS JOIN (VALUES
    ('AUTHORISATIONS', 'Authorisations / Switch Ops',
     'Owns authorisation message quality and the switch. Fixes the cause of integrity and processing-excellence penalties.', 7, 1),
    ('CLEARING_DISPUTES', 'Clearing & Disputes',
     'Owns clearing cut-offs and dispute SLAs. Fixes late presentment and chargeback non-compliance.', 7, 2),
    ('SCHEME_RELATIONS', 'Scheme Relations',
     'Owns the billing relationship: raises disputes with the scheme, keeps ICA/BID registration and contracted rates correct.', 14, 3),
    ('PRODUCT_PRICING', 'Product & Pricing',
     'Owns routing, DCC and merchant mix — the commercial decisions behind cross-border cost.', 21, 4),
    ('FINANCE', 'Finance',
     'Monitors the cost of doing business. Receives what is correct but worth watching, not what is disputable.', 30, 5)
) AS v(team_code, team_name, description, sla, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM sfo_team s WHERE s.tenant_id = t.tenant_id AND s.team_code = v.team_code);

-- ---------------------------------------------------------------------------
-- 2. Routing rules
--
-- match_text is checked against the event code AND the finding's description,
-- case-insensitively, as a substring — because the thing that identifies an
-- integrity penalty is the words "processing integrity", and which field
-- carries them differs by scheme and by invoice layout.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sfo_routing_rule (
    rule_id      BIGSERIAL PRIMARY KEY,
    tenant_id    INT NOT NULL,
    finding_type VARCHAR(30),           -- NULL = any
    category     VARCHAR(30),           -- NULL = any
    scheme       VARCHAR(20),           -- NULL = any
    match_text   VARCHAR(80),           -- NULL = any; lower-cased substring
    team_code    VARCHAR(40) NOT NULL,
    sla_days     INT,                   -- NULL = the team's default
    priority     INT NOT NULL DEFAULT 0,
    note         VARCHAR(300),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(100)
);
CREATE INDEX IF NOT EXISTS idx_sfo_routing_tenant
    ON sfo_routing_rule (tenant_id, priority DESC);
ALTER TABLE sfo_routing_rule ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sfo_routing_rule;
CREATE POLICY tenant_isolation_policy ON sfo_routing_rule USING (tenant_id = get_current_tenant());

INSERT INTO sfo_routing_rule (tenant_id, finding_type, category, scheme, match_text,
                              team_code, sla_days, priority, note)
SELECT t.tenant_id, v.finding_type, v.category, NULL, v.match_text,
       v.team_code, v.sla, v.priority, v.note
FROM tenant t
CROSS JOIN (VALUES
    -- Named causes first. A penalty line usually says what triggered it, and
    -- that text is the only thing that says which team can stop it recurring.
    ('PENALTY', NULL, 'integrity',        'AUTHORISATIONS',    7,  100,
     'Processing / authorisation integrity — fix the auth message, not the invoice'),
    ('PENALTY', NULL, 'processing excellence', 'AUTHORISATIONS', 7, 100,
     'Transaction Processing Excellence assessment — see Mastercard AB605010'),
    ('PENALTY', NULL, 'authoriz',         'AUTHORISATIONS',    7,   95,
     'Authorisation-driven penalty'),
    ('PENALTY', NULL, 'final auth',       'AUTHORISATIONS',    7,   95,
     'Final authorisation not cleared'),
    ('PENALTY', NULL, 'late',             'CLEARING_DISPUTES', 7,   95,
     'Late presentment or late submission — a clearing cut-off was missed'),
    ('PENALTY', NULL, 'presentment',      'CLEARING_DISPUTES', 7,   95,
     'Presentment timing'),
    ('PENALTY', NULL, 'chargeback',       'CLEARING_DISPUTES', 7,   95,
     'Chargeback non-compliance or excessive chargebacks'),
    ('PENALTY', NULL, 'dispute',          'CLEARING_DISPUTES', 7,   95,
     'Dispute handling'),
    -- Then by finding shape.
    ('PENALTY',            NULL, NULL, 'SCHEME_RELATIONS', 7,  60,
     'Any other penalty — disputable, and worth asking what caused it'),
    ('DUPLICATE_CHARGE',   NULL, NULL, 'SCHEME_RELATIONS', 14, 60,
     'Billed twice — raise with the scheme'),
    ('DUPLICATE_MINIMUM',  NULL, NULL, 'SCHEME_RELATIONS', 14, 60,
     'A minimum should top up to a floor once'),
    ('ZERO_QTY_FIXED_FEE', NULL, NULL, 'SCHEME_RELATIONS', 14, 60,
     'An amount with nothing billed behind it'),
    ('RATE_ANOMALY',       NULL, NULL, 'SCHEME_RELATIONS', 14, 60,
     'Check the contracted rate and the latest pricing bulletin'),
    ('UNEXPECTED_EVENT',   NULL, NULL, 'SCHEME_RELATIONS', 21, 60,
     'A new charge — confirm the service was agreed, with Product'),
    -- Then by what kind of cost it is.
    (NULL, 'CROSS_BORDER', NULL, 'PRODUCT_PRICING', 21, 20,
     'Cross-border cost follows routing, DCC and merchant mix'),
    (NULL, 'ASSESSMENTS',  NULL, 'FINANCE',         30, 20,
     'Volume-driven, contractual — monitor rather than dispute'),
    (NULL, 'CLEARING',     NULL, 'FINANCE',         30, 20,
     'Cost of doing business unless something else flagged it'),
    (NULL, 'AUTHORISATION', NULL, 'FINANCE',        30, 20,
     'Cost of doing business unless something else flagged it'),
    -- Catch-all, so nothing is ever orphaned. Visible and editable.
    (NULL, NULL, NULL, 'SCHEME_RELATIONS', 14, 0,
     'Anything no other rule claims')
) AS v(finding_type, category, match_text, team_code, sla, priority, note)
WHERE NOT EXISTS (
    SELECT 1 FROM sfo_routing_rule r
     WHERE r.tenant_id = t.tenant_id
       AND r.finding_type IS NOT DISTINCT FROM v.finding_type
       AND r.category     IS NOT DISTINCT FROM v.category
       AND r.match_text   IS NOT DISTINCT FROM v.match_text
       AND r.team_code = v.team_code);

-- ---------------------------------------------------------------------------
-- 3. Assignment on the finding
--
-- category is carried onto the finding (rather than joined back to the lines)
-- so routing has one row to read, and so the savings centre can be filtered by
-- the kind of cost without touching the ledger.
-- ---------------------------------------------------------------------------
ALTER TABLE sfo_opportunity ADD COLUMN IF NOT EXISTS category   VARCHAR(30);
ALTER TABLE sfo_opportunity ADD COLUMN IF NOT EXISTS team_code  VARCHAR(40);
ALTER TABLE sfo_opportunity ADD COLUMN IF NOT EXISTS assignee   VARCHAR(100);
ALTER TABLE sfo_opportunity ADD COLUMN IF NOT EXISTS due_date   DATE;
ALTER TABLE sfo_opportunity ADD COLUMN IF NOT EXISTS routed_at  TIMESTAMP;
-- RULE = placed by the rule table, MANUAL = a person chose. MANUAL is never
-- overwritten by a re-scan.
ALTER TABLE sfo_opportunity ADD COLUMN IF NOT EXISTS routed_by  VARCHAR(10);

CREATE INDEX IF NOT EXISTS idx_sfo_opp_team
    ON sfo_opportunity (tenant_id, team_code, status, due_date);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_11_13_01__mis_fee_optimizer_routing.sql') ON CONFLICT (filename) DO NOTHING;
