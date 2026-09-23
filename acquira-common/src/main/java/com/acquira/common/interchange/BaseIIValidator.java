package com.acquira.common.interchange;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.acquira.common.interchange.Violation.Category.*;
import static com.acquira.common.interchange.Violation.Severity.*;

/**
 * Layer-1 (intra-file) scheme data-integrity validator for parsed BASE II files.
 *
 * Three groups of checks, all derived from Visa's own edit criteria:
 *   STRUCTURAL — record width, known TC, TCR 0 present per transaction, envelope
 *                (batch trailers + file trailer), batch capacity.
 *   BALANCING  — TC 91/92 control totals vs. the actual records. Counts INCLUDE
 *                the trailer itself (batch: draft TCRs + 1, transactions + 1),
 *                a rule confirmed against a live file.
 *   FIELD_EDIT / REQUIRED — per-field constants, formats (mod-10, dates, MCC,
 *                currency), reserved-blank, and mandatory-present.
 *
 * Severity: ERROR = the file is structurally/arithmetically wrong; WARN =
 * a field-level concern to review; INFO = cosmetic (reserved not blank).
 */
@Component
public class BaseIIValidator {

    private static final int BATCH_CAPACITY = 999; // outgoing CTF default (up to 3300 with VAP)

    private final BaseIILayouts layouts;

    public BaseIIValidator(BaseIILayouts layouts) {
        this.layouts = layouts;
    }

    public List<Violation> validate(ParsedClearingFile pf) {
        List<Violation> out = new ArrayList<>();
        structural(pf, out);
        balancing(pf, out);
        fields(pf, out);
        return out;
    }

    // ── Structural ──────────────────────────────────────────────────────────

    private void structural(ParsedClearingFile pf, List<Violation> out) {
        for (ClearingRecord r : pf.records) {
            if (r.raw.length() != pf.recordBytes) {
                out.add(Violation.of(STRUCTURAL, ERROR, "RECORD_LENGTH",
                                "Record is " + r.raw.length() + " bytes, expected " + pf.recordBytes)
                        .record(r.recordNo, r.tc, r.tcr)
                        .values(String.valueOf(pf.recordBytes), String.valueOf(r.raw.length())));
            }
            if (!isKnownTc(r.tc)) {
                out.add(Violation.of(STRUCTURAL, WARN, "UNKNOWN_TC",
                                "Unrecognised Transaction Code '" + r.tc + "'")
                        .record(r.recordNo, r.tc, r.tcr));
            }
        }

        // Every transaction must begin with TCR 0.
        for (ClearingTransaction t : pf.transactions) {
            if (t.tcrs.isEmpty()) continue;
            String firstTcr = t.tcrs.get(0).tcr.trim();
            if (!"0".equals(firstTcr)) {
                out.add(Violation.of(STRUCTURAL, ERROR, "MISSING_TCR0",
                                "Transaction does not start with TCR 0 (first TCR is '" + firstTcr + "')")
                        .record(t.tcrs.get(0).recordNo, t.tc, firstTcr).txn(t.seqInFile));
            }
        }

        // Each batch must be closed by a TC 91 batch trailer.
        for (ParsedClearingFile.Batch b : pf.batches) {
            if (b.trailer == null) {
                out.add(Violation.of(STRUCTURAL, ERROR, "MISSING_BATCH_TRAILER",
                        "Batch " + b.index + " has no TC 91 batch trailer"));
            }
            if (b.draftTcrCount > BATCH_CAPACITY) {
                out.add(Violation.of(STRUCTURAL, WARN, "BATCH_CAPACITY",
                        "Batch " + b.index + " has " + b.draftTcrCount + " TCRs, over the " + BATCH_CAPACITY + " capacity"));
            }
        }

        // The file must be closed by a TC 92 file trailer.
        if (pf.fileTrailer == null) {
            out.add(Violation.of(STRUCTURAL, ERROR, "MISSING_FILE_TRAILER",
                    "File has no TC 92 file trailer"));
        }
    }

    // ── Balancing ───────────────────────────────────────────────────────────

    private void balancing(ParsedClearingFile pf, List<Violation> out) {
        Map<String, BaseIILayouts.Field> roles = trailerRoles();
        if (roles.isEmpty()) return;

        // Per-batch (TC 91).
        for (ParsedClearingFile.Batch b : pf.batches) {
            if (b.trailer == null) continue;
            long expTcr = b.draftTcrCount + 1;                 // + the trailer itself
            long expTxn = b.transactions.size() + 1;           // + the trailer itself
            long expMon = b.transactions.stream().filter(t -> t.monetary).count();
            long expSrc = sumMinor(b.transactions, 77, 88);
            long expDest = sumMinor(b.transactions, 62, 73);

            checkCount(b.trailer, roles.get("countTcr"), expTcr, "TC91_TCR_COUNT",
                    "Batch " + b.index + " TCR count", out);
            checkCount(b.trailer, roles.get("countTransactions"), expTxn, "TC91_TXN_COUNT",
                    "Batch " + b.index + " transaction count", out);
            checkCount(b.trailer, roles.get("countMonetary"), expMon, "TC91_MONETARY_COUNT",
                    "Batch " + b.index + " monetary count", out);
            checkAmount(b.trailer, roles.get("sumSourceAmount"), expSrc, "TC91_SOURCE_SUM",
                    "Batch " + b.index + " source amount total", out);
            if ("INCOMING".equalsIgnoreCase(pf.direction)) {
                checkAmount(b.trailer, roles.get("sumDestinationAmount"), expDest, "TC91_DEST_SUM",
                        "Batch " + b.index + " destination amount total", out);
            }
        }

        // File (TC 92).
        if (pf.fileTrailer != null) {
            long headers = pf.records.stream().filter(r -> "90".equals(r.tc)).count();
            long expFileTcr = pf.records.size() - headers;                 // all TCRs except TC 90
            long trailers = pf.records.stream().filter(r -> "91".equals(r.tc) || "92".equals(r.tc)).count();
            long expFileTxn = pf.transactions.size() + trailers;           // each trailer counts as a transaction
            long expMon = pf.monetaryCount();
            long expSrc = sumMinor(pf.transactions, 77, 88);
            long expDest = sumMinor(pf.transactions, 62, 73);

            checkCount(pf.fileTrailer, roles.get("countTcr"), expFileTcr, "TC92_TCR_COUNT",
                    "File TCR count", out);
            checkCount(pf.fileTrailer, roles.get("countTransactions"), expFileTxn, "TC92_TXN_COUNT",
                    "File transaction count", out);
            checkCount(pf.fileTrailer, roles.get("countMonetary"), expMon, "TC92_MONETARY_COUNT",
                    "File monetary count", out);
            checkAmount(pf.fileTrailer, roles.get("sumSourceAmount"), expSrc, "TC92_SOURCE_SUM",
                    "File source amount total", out);
            if ("INCOMING".equalsIgnoreCase(pf.direction)) {
                checkAmount(pf.fileTrailer, roles.get("sumDestinationAmount"), expDest, "TC92_DEST_SUM",
                        "File destination amount total", out);
            }
        }
    }

    private void checkCount(ClearingRecord trailer, BaseIILayouts.Field f, long expected,
                            String rule, String what, List<Violation> out) {
        if (f == null) return;
        long actual = longAt(trailer, f);
        if (actual != expected) {
            out.add(Violation.of(BALANCING, ERROR, rule,
                            what + " mismatch: trailer says " + actual + ", file has " + expected)
                    .record(trailer.recordNo, trailer.tc, trailer.tcr)
                    .field(f.name, f.start, f.end, f.req)
                    .values(String.valueOf(expected), String.valueOf(actual)));
        }
    }

    private void checkAmount(ClearingRecord trailer, BaseIILayouts.Field f, long expectedMinor,
                             String rule, String what, List<Violation> out) {
        if (f == null) return;
        long actual = longAt(trailer, f);
        if (actual != expectedMinor) {
            out.add(Violation.of(BALANCING, ERROR, rule,
                            what + " mismatch: trailer says " + money(actual) + ", file sums to " + money(expectedMinor))
                    .record(trailer.recordNo, trailer.tc, trailer.tcr)
                    .field(f.name, f.start, f.end, f.req)
                    .values(money(expectedMinor), money(actual)));
        }
    }

    // ── Field edit / requiredness ────────────────────────────────────────────

    private void fields(ParsedClearingFile pf, List<Violation> out) {
        for (ClearingRecord r : pf.records) {
            BaseIILayouts.RecordLayout rl = layouts.layoutFor(r.tc, r.tcr, pf.direction);
            if (rl == null) continue; // TCR not field-mapped in v1 — framed and counted, not field-checked
            Long txnSeq = txnSeqOf(pf, r);
            for (BaseIILayouts.Field f : rl.fields) {
                String v = r.field(f.start, f.end);
                String trimmed = v.trim();

                // Constant-valued fields.
                if (f.constVal != null && !f.constVal.equals(trimmed)) {
                    out.add(Violation.of(FIELD_EDIT, ERROR, "CONST_" + up(f.name),
                                    f.name + " must be '" + f.constVal + "' but is '" + trimmed + "'")
                            .record(r.recordNo, r.tc, r.tcr).field(f.name, f.start, f.end, f.req).txn(txnSeq)
                            .values(f.constVal, trimmed));
                    continue;
                }

                // Reserved fields should be blank (space) / zero (numeric).
                if (f.reserved) {
                    boolean blank = isUn(f) ? trimmed.chars().allMatch(c -> c == '0') || trimmed.isEmpty()
                                            : trimmed.isEmpty();
                    if (!blank) {
                        out.add(Violation.of(FIELD_EDIT, INFO, "RESERVED_NONBLANK",
                                        "Reserved field '" + f.name + "' is not blank: '" + trimmed + "'")
                                .record(r.recordNo, r.tc, r.tcr).field(f.name, f.start, f.end, f.req).txn(txnSeq));
                    }
                    continue;
                }

                // Mandatory-present (blank = all spaces). Numeric zeros count as present.
                if ("M".equals(f.req) && trimmed.isEmpty()) {
                    out.add(Violation.of(REQUIRED, WARN, "REQUIRED_MISSING",
                                    "Mandatory field '" + f.name + "' is blank")
                            .record(r.recordNo, r.tc, r.tcr).field(f.name, f.start, f.end, f.req).txn(txnSeq));
                }

                // Format / edit checks.
                if (f.check != null && !trimmed.isEmpty()) {
                    String err = runCheck(f.check, v);
                    if (err != null) {
                        out.add(Violation.of(FIELD_EDIT, WARN, "EDIT_" + f.check.toUpperCase(),
                                        f.name + ": " + err)
                                .record(r.recordNo, r.tc, r.tcr).field(f.name, f.start, f.end, f.req).txn(txnSeq)
                                .values(null, trimmed));
                    }
                }

                // Enumerated values.
                if (f.enumRef != null && !trimmed.isEmpty()) {
                    Map<String, String> vals = layouts.enumValues(f.enumRef);
                    if (vals != null && !vals.containsKey(v) && !vals.containsKey(trimmed)) {
                        out.add(Violation.of(FIELD_EDIT, WARN, "EDIT_ENUM",
                                        f.name + " has unlisted value '" + trimmed + "'")
                                .record(r.recordNo, r.tc, r.tcr).field(f.name, f.start, f.end, f.req).txn(txnSeq)
                                .values(null, trimmed));
                    }
                }
            }
        }
    }

    private String runCheck(String check, String raw) {
        String d = raw.trim();
        switch (check) {
            case "mod10_16": {
                String digits = raw.replaceAll("\\D", "");
                if (digits.length() < 12) return "account number too short (" + digits.length() + " digits)";
                if (!luhn(digits)) return "fails mod-10 check (may be a token)";
                return null;
            }
            case "mmdd": {
                if (!d.matches("\\d{4}")) return "not MMDD";
                int mm = Integer.parseInt(d.substring(0, 2)), dd = Integer.parseInt(d.substring(2));
                if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return "invalid MMDD '" + d + "'";
                return null;
            }
            case "yddd": {
                if (d.chars().allMatch(c -> c == '0')) return null; // unpopulated
                if (!d.matches("\\d{4}")) return "not YDDD";
                int ddd = Integer.parseInt(d.substring(1));
                if (ddd < 1 || ddd > 366) return "invalid Julian day '" + d + "'";
                return null;
            }
            case "yyddd": {
                if (!d.matches("\\d{5}")) return "not YYDDD";
                int ddd = Integer.parseInt(d.substring(2));
                if (ddd < 1 || ddd > 366) return "invalid Julian day '" + d + "'";
                return null;
            }
            case "ccy":
                return d.matches("\\d{3}") ? null : "not a 3-digit currency code";
            case "mcc":
                return d.matches("\\d{4}") ? null : "not a 4-digit MCC";
            case "not_all_zero_space":
                return (d.isEmpty() || d.chars().allMatch(c -> c == '0')) ? "must not be all zeros or spaces" : null;
            default:
                return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, BaseIILayouts.Field> trailerRoles() {
        Map<String, BaseIILayouts.Field> m = new HashMap<>();
        BaseIILayouts.RecordLayout rl = layouts.record("TRAILER/0");
        if (rl != null) for (BaseIILayouts.Field f : rl.fields) if (f.role != null) m.put(f.role, f);
        return m;
    }

    private boolean isKnownTc(String tc) {
        return !layouts.describeTc(tc).startsWith("Unknown TC");
    }

    private boolean isUn(BaseIILayouts.Field f) { return "UN".equals(f.fmt) || "N".equals(f.fmt); }

    private long longAt(ClearingRecord r, BaseIILayouts.Field f) {
        String d = r.field(f.start, f.end).replaceAll("\\D", "");
        return d.isEmpty() ? 0L : Long.parseLong(d);
    }

    /** Sum of a numeric field (minor units) over the TCR 0 record of each transaction. */
    private long sumMinor(List<ClearingTransaction> txns, int start, int end) {
        long sum = 0;
        for (ClearingTransaction t : txns) {
            for (ClearingRecord r : t.tcrs) {
                if ("0".equals(r.tcr.trim())) {
                    String d = r.field(start, end).replaceAll("\\D", "");
                    if (!d.isEmpty()) sum += Long.parseLong(d);
                    break;
                }
            }
        }
        return sum;
    }

    private Long txnSeqOf(ParsedClearingFile pf, ClearingRecord r) {
        for (ClearingTransaction t : pf.transactions) {
            for (ClearingRecord tr : t.tcrs) if (tr.recordNo == r.recordNo) return t.seqInFile;
        }
        return null;
    }

    private boolean luhn(String digits) {
        int sum = 0; boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alt) { n *= 2; if (n > 9) n -= 9; }
            sum += n; alt = !alt;
        }
        return sum % 10 == 0;
    }

    private String money(long minor) { return String.format("%.2f", minor / 100.0); }

    private String up(String s) { return s.toUpperCase().replaceAll("[^A-Z0-9]+", "_"); }
}
