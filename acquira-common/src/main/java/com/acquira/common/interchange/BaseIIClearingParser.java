package com.acquira.common.interchange;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Parses a Visa BASE II clearing file (CTF/ITF) into a {@link ParsedClearingFile}.
 *
 * Framing: newline-delimited files are split on line breaks; otherwise the file
 * is framed at a fixed record width (168 CTF, 170 ITF). The TC/TCR state machine
 * follows the spec: a transaction starts at TCR 0 (or when the TC changes / the
 * TCR sequence decreases), TC 90 is the file header, TC 91 closes a batch, TC 92
 * is the file trailer. TCR 0 / TCR 1 are decoded into summary fields for display.
 *
 * The parser is lenient — it never throws on malformed content; structural
 * problems are surfaced by {@link BaseIIValidator}. PANs are never retained in
 * full (only first6..last4).
 */
@Component
public class BaseIIClearingParser {

    private final BaseIILayouts layouts;

    public BaseIIClearingParser(BaseIILayouts layouts) {
        this.layouts = layouts;
    }

    public ParsedClearingFile parse(String rawContent, String fileName, String direction) {
        ParsedClearingFile pf = new ParsedClearingFile();
        pf.fileName = fileName;
        pf.direction = direction == null ? "OUTGOING" : direction.toUpperCase();

        List<String> lines = frame(rawContent, pf);

        // 1. Build physical records. ITF's Record Hash Total (pos 3-4) shifts fields +2.
        int hashShift = pf.recordBytes == 170 ? 2 : 0;
        long recNo = 0;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            recNo++;
            pf.records.add(new ClearingRecord(recNo, line, hashShift));
        }

        // 2. State machine over the records.
        ParsedClearingFile.Batch batch = null;
        ClearingTransaction current = null;
        String prevTc = null;
        int prevTcrNum = -1;
        long txnSeq = 0;
        int batchIndex = 0;

        for (ClearingRecord r : pf.records) {
            String tc = r.tc;

            if ("90".equals(tc)) {                       // file header
                pf.header = r;
                decodeHeader(pf, r);
                current = null; prevTc = null; prevTcrNum = -1;
                continue;
            }
            if ("92".equals(tc)) {                       // file trailer
                pf.fileTrailer = r;
                current = null;
                continue;
            }
            if ("91".equals(tc)) {                       // batch trailer — close current batch
                if (batch == null) { batch = new ParsedClearingFile.Batch(); batch.index = ++batchIndex; pf.batches.add(batch); }
                batch.trailer = r;
                batch = null;                            // next transaction opens a fresh batch
                current = null; prevTc = null; prevTcrNum = -1;
                continue;
            }

            // A transaction component record. Ensure an open batch.
            if (batch == null) { batch = new ParsedClearingFile.Batch(); batch.index = ++batchIndex; pf.batches.add(batch); }

            int tcrNum = tcrNum(r.tcr);
            boolean startsNewTxn = current == null
                    || "0".equals(r.tcr.trim())
                    || !tc.equals(prevTc)
                    || (tcrNum >= 0 && prevTcrNum >= 0 && tcrNum < prevTcrNum);

            if (startsNewTxn) {
                current = new ClearingTransaction();
                current.seqInFile = ++txnSeq;
                current.tc = tc;
                current.batchNumber = batch.index;
                pf.transactions.add(current);
                batch.transactions.add(current);
            }
            current.tcrs.add(r);
            if (layouts.isDraftData(tc)) batch.draftTcrCount++;

            decodeInto(current, r);

            prevTc = tc;
            prevTcrNum = tcrNum;
        }

        return pf;
    }

    // ── Framing ────────────────────────────────────────────────────────────────

    private List<String> frame(String raw, ParsedClearingFile pf) {
        if (raw == null) raw = "";
        if (raw.indexOf('\n') >= 0) {
            String[] parts = raw.split("\r?\n", -1);
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            int max = 0;
            for (String p : parts) {
                if (p.isEmpty()) continue;
                out.add(p);
                if (p.length() > max) max = p.length();
            }
            pf.recordBytes = max >= 170 ? 170 : 168;
            pf.format = pf.recordBytes == 170 ? "ITF" : "CTF";
            return out;
        }
        // Undelimited stream: choose fixed width.
        int width = 168;
        if (raw.length() % 168 != 0 && raw.length() % 170 == 0) width = 170;
        pf.recordBytes = width;
        pf.format = width == 170 ? "ITF" : "CTF";
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; i + width <= raw.length(); i += width) out.add(raw.substring(i, i + width));
        int rem = raw.length() % width;
        if (rem != 0) out.add(raw.substring(raw.length() - rem)); // short tail — validator flags it
        return out;
    }

    // ── Decoding ────────────────────────────────────────────────────────────────

    private void decodeHeader(ParsedClearingFile pf, ClearingRecord r) {
        BaseIILayouts.RecordLayout rl = layouts.layoutFor("90", "0", pf.direction);
        if (rl == null) return;
        for (BaseIILayouts.Field f : rl.fields) {
            String v = r.field(f.start, f.end);
            switch (f.name) {
                case "Center Information Block": pf.centerInfoBlock = v.trim(); break;
                case "Processing Date (YYDDD)": pf.processingDate = yyddd(v); break;
                case "Settlement Date (YYDDD)": pf.settlementDate = yyddd(v); break;
                case "Test Option": pf.testFile = "TEST".equalsIgnoreCase(v.trim()); break;
                default: break;
            }
        }
    }

    private void decodeInto(ClearingTransaction t, ClearingRecord r) {
        String seq = r.tcr.trim();
        if ("0".equals(seq) && layouts.isDraftData(r.tc)) {
            t.qualifier = r.field(3, 3);
            t.accountMasked = mask(r.field(5, 20));
            t.acquirerRefNumber = r.field(27, 49).trim();
            t.acquirerBid = r.field(50, 57).trim();
            t.purchaseDate = r.field(58, 61).trim();
            t.destinationAmount = amount(r.field(62, 73));
            t.destinationCcy = r.field(74, 76).trim();
            t.sourceAmount = amount(r.field(77, 88));
            t.sourceCcy = r.field(89, 91).trim();
            t.merchantName = r.field(92, 116).trim();
            t.merchantCountry = r.field(130, 132).trim();
            t.mcc = r.field(133, 136).trim();
            t.reimbursementAttr = r.field(168, 168).trim();
            // reversals (25/26/27/35/36/37) and disputes are still monetary; keep true.
        } else if ("1".equals(seq) && layouts.isDraftData(r.tc)) {
            String fpi = r.field(76, 78).trim();
            if (!fpi.isEmpty()) t.feeProgramIndicator = fpi;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int tcrNum(String tcr) {
        String s = tcr == null ? "" : tcr.trim();
        if (s.length() == 1 && Character.isDigit(s.charAt(0))) return s.charAt(0) - '0';
        return -1; // letter TCRs (D, E) sort after digits; treated as "not decreasing"
    }

    private String mask(String pan) {
        String d = pan == null ? "" : pan.replaceAll("\\D", "");
        if (d.length() < 10) return d.isEmpty() ? null : "*".repeat(d.length());
        return d.substring(0, 6) + "*".repeat(d.length() - 10) + d.substring(d.length() - 4);
    }

    private BigDecimal amount(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.isEmpty()) return BigDecimal.ZERO;
        return new BigDecimal(d).movePointLeft(2);
    }

    private LocalDate yyddd(String raw) {
        String d = raw == null ? "" : raw.trim();
        if (d.length() != 5 || !d.chars().allMatch(Character::isDigit)) return null;
        int yy = Integer.parseInt(d.substring(0, 2));
        int ddd = Integer.parseInt(d.substring(2));
        if (ddd < 1 || ddd > 366) return null;
        try {
            return LocalDate.ofYearDay(2000 + yy, ddd);
        } catch (Exception e) {
            return null;
        }
    }
}
