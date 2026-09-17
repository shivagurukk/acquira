package com.acquira.common.interchange;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser + Layer-1 validator regression, on a synthetic CTF (no real card data).
 * Mirrors the structure proven against a live Visa file: TC 90 header, one TC 05
 * draft transaction, TC 91 batch trailer, TC 92 file trailer — with the trailer
 * counts that INCLUDE the trailer itself.
 */
class BaseIIClearingParserTest {

    private final BaseIILayouts layouts = new BaseIILayouts();
    private final BaseIIClearingParser parser = new BaseIIClearingParser(layouts);
    private final BaseIIValidator validator = new BaseIIValidator(layouts);

    /** Fill a 168-char record with spaces, then overlay values at 1-based positions. */
    private static String rec(String... posVal) {
        char[] b = new char[168];
        Arrays.fill(b, ' ');
        for (int i = 0; i < posVal.length; i += 2) {
            int start = Integer.parseInt(posVal[i]);
            String v = posVal[i + 1];
            for (int j = 0; j < v.length() && start - 1 + j < 168; j++) b[start - 1 + j] = v.charAt(j);
        }
        return new String(b);
    }

    private String header() {
        return rec("1", "90", "3", "404095", "9", "26247"); // TC90, CIB, proc date YYDDD
    }

    private String draftTcr0() {
        return rec(
                "1", "05", "3", "0", "4", "0",
                "5", "4111111111111111",          // Luhn-valid test PAN
                "21", "000",
                "27", "74040956247810499750052",  // ARN (23)
                "50", "10033585",                 // BID
                "58", "0902",                     // purchase MMDD
                "62", "000000000000",             // destination amount
                "77", "000000001000",             // source amount = 10.00
                "89", "784",                      // source ccy
                "92", "TEST MERCHANT",
                "117", "DUBAI",
                "130", "AE ",
                "133", "7230",                    // MCC
                "147", "1",                       // usage
                "150", "9",                       // settlement flag
                "164", "0000",                    // central processing YDDD (unpopulated)
                "168", "B");
    }

    private String batchTrailer(String txnCount) {
        return rec("1", "91", "3", "0", "4", "0",
                "5", "000000",                    // CIB (outgoing zeros)
                "11", "00000",                    // processing date (outgoing zeros)
                "16", "000000000000000",          // destination amount sum
                "31", "000000000001",             // monetary count = 1
                "43", "000001",                   // batch number
                "49", "000000000002",             // TCR count = draft(1) + trailer(1)
                "75", txnCount,                   // transaction count = txns(1) + trailer(1) = 2
                "102", "000000000001000");        // source amount sum = 1000
    }

    private String fileTrailer() {
        return rec("1", "92", "3", "0", "4", "0",
                "5", "000000", "11", "00000",
                "16", "000000000000000",
                "31", "000000000001",             // monetary = 1
                "43", "000001",
                "49", "000000000003",             // file TCR count = records(4) - header(1) = 3
                "75", "000000003",                // file txn count = txns(1) + trailers(2) = 3
                "102", "000000000001000");
    }

    private String file(String batchTxnCount) {
        return String.join("\n", header(), draftTcr0(), batchTrailer(batchTxnCount), fileTrailer());
    }

    @Test
    void parsesStructureAndDecodesTcr0() {
        ParsedClearingFile pf = parser.parse(file("000000002"), "TEST.CTF", "OUTGOING");

        assertEquals(4, pf.records.size());
        assertEquals(1, pf.transactions.size());
        assertEquals(1, pf.batches.size());
        assertNotNull(pf.header);
        assertNotNull(pf.fileTrailer);
        assertEquals("CTF", pf.format);
        assertEquals(168, pf.recordBytes);

        ClearingTransaction t = pf.transactions.get(0);
        assertEquals("05", t.tc);
        assertEquals("74040956247810499750052", t.acquirerRefNumber);
        assertEquals("7230", t.mcc);
        assertEquals("784", t.sourceCcy);
        assertEquals(0, t.sourceAmount.compareTo(new java.math.BigDecimal("10.00")));
        assertTrue(t.accountMasked.startsWith("411111") && t.accountMasked.endsWith("1111"));
        assertFalse(t.accountMasked.contains("4111111111111111"), "PAN must be masked");
    }

    @Test
    void validBalancingProducesNoErrors() {
        ParsedClearingFile pf = parser.parse(file("000000002"), "TEST.CTF", "OUTGOING");
        List<Violation> vs = validator.validate(pf);
        long errors = vs.stream().filter(v -> v.severity == Violation.Severity.ERROR).count();
        assertEquals(0, errors, "well-formed file should have no ERROR violations; got: " +
                vs.stream().filter(v -> v.severity == Violation.Severity.ERROR)
                        .map(v -> v.ruleCode + " " + v.message).toList());
    }

    @Test
    void tamperedTransactionCountTripsBalancingError() {
        // Batch trailer claims 9 transactions instead of the correct 2.
        ParsedClearingFile pf = parser.parse(file("000000009"), "TEST.CTF", "OUTGOING");
        List<Violation> vs = validator.validate(pf);
        assertTrue(vs.stream().anyMatch(v ->
                        v.category == Violation.Category.BALANCING
                        && v.severity == Violation.Severity.ERROR
                        && "TC91_TXN_COUNT".equals(v.ruleCode)),
                "expected a TC91 transaction-count balancing error");
    }

    // ── Incoming ITF (170-byte, Record Hash Total at 3-4 shifts fields +2) ────

    /** Fill a 170-char ITF record: TC at 1-2, hash "00" at 3-4, other values shifted +2. */
    private static String itfRec(String... posVal) {
        char[] b = new char[170];
        Arrays.fill(b, ' ');
        b[2] = '0'; b[3] = '0';                    // Record Hash Total placeholder
        for (int i = 0; i < posVal.length; i += 2) {
            int pos = Integer.parseInt(posVal[i]);
            int start = pos >= 3 ? pos + 2 : pos;  // CTF coordinates in, ITF positions out
            String v = posVal[i + 1];
            for (int j = 0; j < v.length() && start - 1 + j < 170; j++) b[start - 1 + j] = v.charAt(j);
        }
        return new String(b);
    }

    private String itfFile() {
        String header = itfRec("1", "90", "3", "404095", "9", "26247", "20", "26248"); // + settlement date
        String draft = itfRec(
                "1", "05", "3", "0", "4", "0",
                "5", "4111111111111111",
                "27", "74040956247810499750052",
                "50", "10033585",
                "58", "0902",
                "62", "000000001000",             // destination amount = 10.00 (checked on incoming)
                "74", "048",
                "77", "000000001000",
                "89", "784",
                "92", "TEST MERCHANT",
                "117", "MANAMA",
                "130", "BH ",
                "133", "7230",
                "147", "1", "150", "9", "164", "0000");
        String tc91 = itfRec("1", "91", "3", "0", "4", "0",
                "5", "404095", "11", "26247",
                "16", "000000000001000",
                "31", "000000000001",
                "43", "000001",
                "49", "000000000002",
                "75", "000000002",
                "102", "000000000001000");
        String tc92 = itfRec("1", "92", "3", "0", "4", "0",
                "5", "404095", "11", "26247",
                "16", "000000000001000",
                "31", "000000000001",
                "43", "000001",
                "49", "000000000003",
                "75", "000000003",
                "102", "000000000001000");
        return String.join("\n", header, draft, tc91, tc92);
    }

    @Test
    void parsesIncomingItfWithHashShift() {
        ParsedClearingFile pf = parser.parse(itfFile(), "INCOMING.ITF", "INCOMING");

        assertEquals("ITF", pf.format);
        assertEquals(170, pf.recordBytes);
        assertEquals(1, pf.transactions.size());
        assertNotNull(pf.header);
        assertNotNull(pf.fileTrailer);
        assertEquals(java.time.LocalDate.ofYearDay(2026, 248), pf.settlementDate);

        ClearingTransaction t = pf.transactions.get(0);
        assertEquals("05", t.tc);
        assertEquals("74040956247810499750052", t.acquirerRefNumber);
        assertEquals("7230", t.mcc);
        assertEquals("048", t.destinationCcy);
        assertEquals(0, t.destinationAmount.compareTo(new java.math.BigDecimal("10.00")));
        assertTrue(t.accountMasked.startsWith("411111") && t.accountMasked.endsWith("1111"));
    }

    @Test
    void incomingItfBalancesWithNoErrors() {
        ParsedClearingFile pf = parser.parse(itfFile(), "INCOMING.ITF", "INCOMING");
        List<Violation> vs = validator.validate(pf);
        long errors = vs.stream().filter(v -> v.severity == Violation.Severity.ERROR).count();
        assertEquals(0, errors, "well-formed incoming ITF should have no ERROR violations; got: " +
                vs.stream().filter(v -> v.severity == Violation.Severity.ERROR)
                        .map(v -> v.ruleCode + " " + v.message).toList());
    }

    @Test
    void missingFileTrailerIsStructuralError() {
        String noTrailer = String.join("\n", header(), draftTcr0(), batchTrailer("000000002"));
        ParsedClearingFile pf = parser.parse(noTrailer, "TEST.CTF", "OUTGOING");
        List<Violation> vs = validator.validate(pf);
        assertTrue(vs.stream().anyMatch(v -> "MISSING_FILE_TRAILER".equals(v.ruleCode)));
    }
}
