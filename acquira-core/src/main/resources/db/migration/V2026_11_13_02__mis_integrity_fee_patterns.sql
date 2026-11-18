-- ============================================================================
-- V2026_11_13_02: MIS / Scheme Fee Optimizer — classify integrity and
-- processing-excellence charges as penalties.
--
-- WHY: "Authorization Processing Excellence Assessment" was reading as an
-- AUTHORISATION cost, because the patterns matched the word "authoriz" at a
-- higher priority than anything else on the line. It is not a cost of
-- authorising — it is a charge for failing a processing standard, which means
-- it is avoidable, disputable, and belongs in front of the team that owns the
-- authorisation message rather than in Finance's "cost of doing business".
--
-- Mastercard bills these under the Transaction Processing Excellence (TPE) and
-- processing-integrity programmes and substantiates them in the acquirer
-- detail reports AB602010-FF, AB605010-AA and AB605010-FF (MCBS Reports,
-- 2 June 2026) — the transaction-level evidence for any dispute.
--
-- V2026_11_13_01 is already applied, so this corrects it in a NEW script
-- rather than editing that one.
--
-- Idempotent; splitter-safe (no dollar-quoting). On prod apply once via psql.
-- ============================================================================

INSERT INTO sfo_classification_pattern (scheme, match_text, category, confidence, priority)
SELECT v.* FROM (VALUES
    -- Above the 'authoriz' pattern (70) and the 'assessment' pattern (50), so
    -- the programme name wins over the activity it is levied against.
    (NULL,'processing integrity','PENALTIES',90,105),
    (NULL,'processing excellence','PENALTIES',90,105),
    (NULL,'integrity fee','PENALTIES',85,105),
    (NULL,'integrity','PENALTIES',75,102),
    (NULL,'excellence assessment','PENALTIES',85,105),
    (NULL,'final authorization','PENALTIES',80,102),
    (NULL,'pre-authorization not','PENALTIES',80,102),
    (NULL,'undefined transaction','PENALTIES',70,101)
) AS v(scheme, match_text, category, confidence, priority)
WHERE NOT EXISTS (
    SELECT 1 FROM sfo_classification_pattern p
     WHERE p.scheme IS NOT DISTINCT FROM v.scheme AND p.match_text = v.match_text);

-- Re-read every line already imported under the old patterns. Only lines the
-- PATTERN matcher decided are touched: an override or a settled dictionary
-- entry is somebody's decision and is left exactly as it is.
UPDATE sfo_fee_line l
   SET category   = p.category,
       confidence = p.confidence
  FROM (
    SELECT DISTINCT ON (f.line_id) f.line_id, c.category, c.confidence
      FROM sfo_fee_line f
      JOIN sfo_classification_pattern c
        ON POSITION(c.match_text IN LOWER(COALESCE(f.description,'') || ' '
                                       || COALESCE(f.event_code,''))) > 0
     WHERE f.classified_by = 'PATTERN'
     ORDER BY f.line_id, c.priority DESC, c.confidence DESC
  ) p
 WHERE l.line_id = p.line_id
   AND l.category IS DISTINCT FROM p.category;

-- The dictionary learned the old verdict for these codes; correct the rows it
-- is still guessing at so the next invoice does not re-apply the old reading.
UPDATE sfo_event_dictionary d
   SET category   = p.category,
       confidence = p.confidence,
       updated_at = CURRENT_TIMESTAMP
  FROM (
    SELECT DISTINCT ON (e.dict_id) e.dict_id, c.category, c.confidence
      FROM sfo_event_dictionary e
      JOIN sfo_classification_pattern c
        ON POSITION(c.match_text IN LOWER(COALESCE(e.description,'') || ' '
                                       || COALESCE(e.event_code,''))) > 0
     WHERE e.learned = TRUE
     ORDER BY e.dict_id, c.priority DESC, c.confidence DESC
  ) p
 WHERE d.dict_id = p.dict_id
   AND d.category IS DISTINCT FROM p.category;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_11_13_02__mis_integrity_fee_patterns.sql') ON CONFLICT (filename) DO NOTHING;
