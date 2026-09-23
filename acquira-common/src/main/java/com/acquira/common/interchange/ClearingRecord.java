package com.acquira.common.interchange;

/**
 * One physical BASE II record (a TCR): a fixed 168-byte line (170 for ITF).
 * recordNo is the 1-based index within the file.
 */
public class ClearingRecord {
    public final long recordNo;
    public final String tc;    // positions 1-2
    public final String tcr;   // position 4 in CTF coordinates (sequence number / letter)
    public final String raw;   // the verbatim record text
    /** 0 for CTF; 2 for ITF, whose Record Hash Total at pos 3-4 shifts all later fields. */
    public final int hashShift;

    public ClearingRecord(long recordNo, String raw) {
        this(recordNo, raw, 0);
    }

    public ClearingRecord(long recordNo, String raw, int hashShift) {
        this.recordNo = recordNo;
        this.raw = raw;
        this.hashShift = hashShift;
        this.tc = slice(1, 2).trim();
        this.tcr = field(4, 4);
    }

    /**
     * Slice in CTF coordinates: positions 3+ are shifted by the ITF record hash
     * when present, so layouts stay written once (in CTF positions) for both formats.
     */
    public String field(int start, int end) {
        return start >= 3 ? slice(start + hashShift, end + hashShift) : slice(start, end);
    }

    /** 1-based inclusive slice, bounds-safe (short records return what exists). */
    public String slice(int start, int end) {
        if (raw == null) return "";
        int s = Math.max(0, start - 1);
        int e = Math.min(raw.length(), end);
        if (s >= e) return "";
        return raw.substring(s, e);
    }
}
