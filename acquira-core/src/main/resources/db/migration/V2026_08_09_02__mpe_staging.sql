-- ============================================================================
-- V2026_08_09_02: Mastercard IPM MPE (T067/T068) staged intake.
--
-- SCOPE (2026-08-09): STAGE + VALIDATE ONLY. The container format, file
-- header ("UPDATE FILE" / "REPLACEMENT FILE"), IP0000T1 directory and the
-- per-table/final trailers are parsed per the Dual Message Clearing System
-- Parameter Table Layouts DITA documentation (verified field-for-field
-- against AFS's live TT067 delivery). The INNER field mapping of the account
-- range table (IP0040T1) in AFS's extract is an OLDER table edition than the
-- June-2026 manual (declared record length 307 vs manual 313; compressed
-- field order differs), so promotion of ranges into ref_bin_range is
-- deliberately DEFERRED until the T068 full-file replacement confirms the
-- positions empirically. Nothing reads these tables yet.
--
-- mpe_file            one row per uploaded delivery (checksum-deduped)
-- mpe_table_directory the file's own IP0000T1 records, decoded (the file
--                     declares its tables, key positions, record lengths,
--                     versions and 3-char sub-ids)
-- mpe_record          every data record, keyed by table sub-id, raw text
--                     preserved verbatim (fixed-width spaces intact)
--
-- Idempotent + splitter-safe.
-- ============================================================================

CREATE TABLE IF NOT EXISTS mpe_file (
    id               BIGSERIAL PRIMARY KEY,
    file_name        VARCHAR(200) NOT NULL,
    file_type        VARCHAR(10)  NOT NULL,     -- T067 (update) / T068 (replacement)
    header_text      VARCHAR(80),
    created_date     VARCHAR(20),               -- from header, as delivered
    created_time     VARCHAR(20),
    sha256           VARCHAR(64) NOT NULL UNIQUE, -- duplicate-delivery guard
    record_count     INT,
    trailer_total    INT,                       -- final TABLEZZZZ trailer count
    status           VARCHAR(20) NOT NULL,      -- STAGED / COUNT_MISMATCH
    loaded_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mpe_table_directory (
    id               BIGSERIAL PRIMARY KEY,
    file_id          BIGINT NOT NULL REFERENCES mpe_file(id) ON DELETE CASCADE,
    subject_table    VARCHAR(8)  NOT NULL,      -- e.g. IP0040T1
    subject_name     VARCHAR(30),               -- e.g. ACCOUNT RANGE TABLE
    sub_id           VARCHAR(3),                -- 3-char compressed table id
    key_length       INT,
    key_start        INT,
    rec_len_min      INT,
    rec_len_max      INT,
    version          VARCHAR(8),
    declared_count   INT,                       -- from that table's trailer
    staged_count     INT                        -- records actually staged
);
CREATE INDEX IF NOT EXISTS idx_mpe_dir_file ON mpe_table_directory (file_id);

CREATE TABLE IF NOT EXISTS mpe_record (
    id               BIGSERIAL PRIMARY KEY,
    file_id          BIGINT NOT NULL REFERENCES mpe_file(id) ON DELETE CASCADE,
    sub_id           VARCHAR(3) NOT NULL,
    active_flag      CHAR(1),
    effective_raw    VARCHAR(10),               -- as delivered (edition-dependent format)
    record_text      TEXT NOT NULL              -- verbatim, spaces preserved
);
CREATE INDEX IF NOT EXISTS idx_mpe_record_file_sub ON mpe_record (file_id, sub_id);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_09_02__mpe_staging.sql') ON CONFLICT (filename) DO NOTHING;
