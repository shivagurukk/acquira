package com.acquira.common.interchange;

/**
 * A single scheme data-integrity violation found in a clearing file.
 * Maps 1:1 to a row in interchange_violation.
 */
public class Violation {
    public enum Category { STRUCTURAL, BALANCING, FIELD_EDIT, REQUIRED, DIMP }
    public enum Severity { ERROR, WARN, INFO }

    public Category category;
    public Severity severity;
    public String ruleCode;         // machine key, e.g. PAN_MOD10
    public String message;          // human-readable
    public Long recordNo;           // physical record index, or null
    public Long transactionSeq;     // links to ClearingTransaction.seqInFile, or null for file/batch-level
    public String tc;
    public String tcr;
    public String fieldName;
    public String fieldPosition;    // "27-49"
    public String requiredness;     // M | C | O
    public String expected;
    public String actual;

    public static Violation of(Category cat, Severity sev, String rule, String msg) {
        Violation v = new Violation();
        v.category = cat;
        v.severity = sev;
        v.ruleCode = rule;
        v.message = msg;
        return v;
    }

    public Violation record(Long recordNo, String tc, String tcr) {
        this.recordNo = recordNo; this.tc = tc; this.tcr = tcr; return this;
    }

    public Violation field(String name, int start, int end, String req) {
        this.fieldName = name; this.fieldPosition = start + "-" + end; this.requiredness = req; return this;
    }

    public Violation txn(Long seq) { this.transactionSeq = seq; return this; }

    public Violation values(String expected, String actual) {
        this.expected = expected; this.actual = actual; return this;
    }
}
