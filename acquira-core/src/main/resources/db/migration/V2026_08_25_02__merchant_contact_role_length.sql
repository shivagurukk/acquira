-- V2026_08_25_02 — Widen merchant_contact.role from VARCHAR(50) to VARCHAR(150).
--
-- WHY: the merchant master ingest (MerchantMasterJobConfig, contacts step) maps
-- primary_contact_designation / secondary_contact_designation — both VARCHAR(100)
-- in stg_merchant_master_raw — into merchant_contact.role, which was VARCHAR(50).
-- A designation longer than 50 chars (common: full job titles) aborts the contacts
-- INSERT with "value too long for type character varying(50)", failing the upload.
-- 150 matches contact_name/email and leaves headroom over the 100-char source.
--
-- Idempotent + online: widening a varchar length is a metadata-only change in
-- Postgres (no table rewrite, brief ACCESS EXCLUSIVE lock only).

ALTER TABLE merchant_contact ALTER COLUMN role TYPE VARCHAR(150);
