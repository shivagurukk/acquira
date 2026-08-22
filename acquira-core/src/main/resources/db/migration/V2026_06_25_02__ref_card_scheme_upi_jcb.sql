-- ============================================================
-- Migration: V2026_06_25_02__ref_card_scheme_upi_jcb
-- Purpose:   Fix card_type for UPI and JCB in ref_card_scheme
--            so transactionTenantProcessor maps them to CREDIT.
--
-- Background:
--   UPI (id=8) and JCB (id=9) were seeded in schema.sql with
--   card_type=0 (Generic). The transactionTenantProcessor switch
--   maps card_type: 0=DEBIT, 1=CREDIT, 2=DEBIT, 3=CREDIT, 4=DEBIT.
--   With card_type=0 both were bucketed as DEBIT, causing:
--     - Credit card count understated on P8 card analytics
--     - Debit count overstated
--
--   JCB cards from UnionPay terminal feeds also arrive with
--   CardScheme = 'NULL' (literal string). The batch SQL fix maps
--   NULL scheme rows to their card_type ('JCB') so this row being
--   correct in ref_card_scheme closes the full chain.
--
--   card_type=1 = Credit (per existing ref_card_scheme comments).
--   Both UPI (UnionPay International) and JCB are credit instruments.
--
-- Safe to re-run: UPDATE on PK id — idempotent.
-- ============================================================

UPDATE ref_card_scheme SET card_type = 1 WHERE code = 'UPI';
UPDATE ref_card_scheme SET card_type = 1 WHERE code = 'JCB';
