package com.acquira.common.interchange;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mastercard IPM parser + validator regression on a synthetic EBCDIC file
 * (no real card data). Exercises 1014 de-blocking, 4-byte RDW framing, MTI,
 * primary + secondary bitmap, and fixed / LLVAR DE decode — the structure
 * proven against a live Mastercard file.
 */
class IpmClearingParserTest {

    private final IpmLayouts layouts = new IpmLayouts();
    private final IpmClearingParser parser = new IpmClearingParser(layouts);
    private final IpmValidator validator = new IpmValidator(layouts, new DimpEngine());

    private static final Charset CP037 = Charset.forName("Cp037");
    private static byte[] ebc(String s) { return s.getBytes(CP037); }

    /** Primary (+ secondary when any DE > 64) bitmap for a DE set. */
    private static byte[] bitmap(Set<Integer> des) {
        boolean secondary = des.stream().anyMatch(d -> d > 64);
        Set<Integer> prim = new TreeSet<>(des);
        if (secondary) prim.add(1);
        byte[] out = new byte[secondary ? 16 : 8];
        for (int d : prim) {
            if (d <= 64) { int i = d - 1; out[i / 8] |= (0x80 >> (i % 8)); }
            else if (secondary) { int i = d - 65; out[8 + i / 8] |= (0x80 >> (i % 8)); }
        }
        return out;
    }

    /** Build one record: 4-byte RDW length + MTI + bitmap + DE data (DE order). */
    private static byte[] record(String mti, LinkedHashMap<Integer, byte[]> de) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(ebc(mti));
        body.write(bitmap(de.keySet()));
        for (byte[] d : de.values()) body.write(d);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream rec = new ByteArrayOutputStream();
        rec.write(new byte[]{ (byte)(b.length >>> 24), (byte)(b.length >>> 16), (byte)(b.length >>> 8), (byte) b.length });
        rec.write(b);
        return rec.toByteArray();
    }

    private LinkedHashMap<Integer, byte[]> presentment(String amount, String pan, String trailerNo) {
        LinkedHashMap<Integer, byte[]> de = new LinkedHashMap<>();
        de.put(2, ebc(String.format("%02d", pan.length()) + pan)); // LLVAR PAN
        de.put(3, ebc("000000"));
        de.put(4, ebc(amount));                                    // n-12
        de.put(12, ebc("260902113945"));
        de.put(24, ebc("200"));
        de.put(26, ebc("7230"));
        de.put(49, ebc("840"));
        return de;
    }

    private LinkedHashMap<Integer, byte[]> admin(String msgNo) {
        LinkedHashMap<Integer, byte[]> de = new LinkedHashMap<>();
        de.put(24, ebc("697"));
        de.put(71, ebc(msgNo));                                    // n-8, > 64 -> secondary bitmap
        return de;
    }

    private byte[] file(String trailerNo) throws Exception {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(record("1644", admin("00000001")));
        f.write(record("1240", presentment("000000002000", "5551231212127621", null)));
        f.write(record("1240", presentment("000000001000", "5551231212127621", null)));
        f.write(record("1644", admin(trailerNo)));
        return f.toByteArray();
    }

    @Test
    void parsesFramingMtisAndDecodesPresentment() throws Exception {
        ParsedIpmFile pf = parser.parse(file("00000004"), "TEST.IPM", "INCOMING");

        assertEquals(4, pf.recordCount);
        assertEquals(List.of("1644", "1240", "1240", "1644"),
                pf.messages.stream().map(m -> m.mti).toList());

        IpmMessage p = pf.presentments().get(0);
        assertTrue(p.fullyDecoded, "presentment should fully decode; notes=" + p.notes);
        assertEquals("000000002000", p.get(4));
        assertEquals("7230", p.get(26));
        assertEquals("840", p.get(49));
        assertTrue(p.get(2).startsWith("555123") && p.get(2).endsWith("7621"));
        assertFalse(p.get(2).contains("5551231212127621"), "PAN must be masked");

        assertNotNull(pf.header);
        assertNotNull(pf.trailer);
        assertEquals("00000004", pf.trailer.get(71));
    }

    @Test
    void validTrailerCountNoErrors() throws Exception {
        ParsedIpmFile pf = parser.parse(file("00000004"), "TEST.IPM", "INCOMING");
        long errors = validator.validate(pf).stream().filter(v -> v.severity == Violation.Severity.ERROR).count();
        assertEquals(0, errors, "well-formed IPM should have no ERROR violations");
    }

    @Test
    void wrongTrailerCountTripsError() throws Exception {
        ParsedIpmFile pf = parser.parse(file("00000009"), "TEST.IPM", "INCOMING"); // says 9, actual 4
        assertTrue(validator.validate(pf).stream().anyMatch(v ->
                "TRAILER_COUNT".equals(v.ruleCode) && v.severity == Violation.Severity.ERROR));
    }

    @Test
    void dimpFileOnlyEditsFireAsWarnings() throws Exception {
        // The synthetic presentments carry no DE 42/43/22, so DIMP edits 2/3/7 fire.
        ParsedIpmFile pf = parser.parse(file("00000004"), "TEST.IPM", "INCOMING");
        List<Violation> vs = validator.validate(pf);
        assertTrue(vs.stream().anyMatch(v -> v.category == Violation.Category.DIMP && v.ruleCode.startsWith("DIMP_E3")),
                "expected DIMP Edit 3 (INV DE43)");
        assertTrue(vs.stream().anyMatch(v -> v.category == Violation.Category.DIMP && v.ruleCode.startsWith("DIMP_E2")),
                "expected DIMP Edit 2 (Merchant ID)");
        assertTrue(vs.stream().filter(v -> v.category == Violation.Category.DIMP)
                        .allMatch(v -> v.severity == Violation.Severity.WARN),
                "DIMP edits are warnings, not errors");
    }

    @Test
    void deblocks1014WrappedFile() throws Exception {
        byte[] raw = file("00000004");
        assertTrue(raw.length < 1012, "fixture must fit one block for this test");
        byte[] block = new byte[1014];
        Arrays.fill(block, (byte) 0x40);           // EBCDIC space padding
        System.arraycopy(raw, 0, block, 0, raw.length);
        block[1012] = 0; block[1013] = 0;          // block terminator

        ParsedIpmFile pf = parser.parse(block, "TEST.IPM", "INCOMING");
        assertTrue(pf.blocked, "should detect 1014 blocking");
        assertEquals(4, pf.recordCount);
        assertEquals("1240", pf.messages.get(1).mti);
    }
}
