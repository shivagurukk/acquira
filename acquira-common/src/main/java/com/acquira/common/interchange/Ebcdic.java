package com.acquira.common.interchange;

import java.nio.charset.Charset;

/**
 * EBCDIC (IBM CP037) helpers for Mastercard IPM decoding. Mastercard clearing
 * files are EBCDIC with zoned-decimal numerics (F0–F9). We use the JVM's Cp037
 * charset for text and a fast path for zoned digits.
 */
public final class Ebcdic {
    private Ebcdic() {}

    private static final Charset CP037 = pick();

    private static Charset pick() {
        for (String name : new String[]{"Cp037", "IBM037", "IBM-037"}) {
            try { return Charset.forName(name); } catch (Exception ignore) { }
        }
        return Charset.forName("ISO-8859-1"); // last resort; digits still handled below
    }

    /** Decode an EBCDIC byte range to a String. */
    public static String decode(byte[] b, int off, int len) {
        if (b == null || len <= 0 || off >= b.length) return "";
        int end = Math.min(off + len, b.length);
        return new String(b, off, end - off, CP037);
    }

    public static String decode(byte[] b) { return decode(b, 0, b == null ? 0 : b.length); }

    /** Zoned-decimal digits (F0–F9) to their numeric characters; other bytes as-is via CP037. */
    public static String digits(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(Math.max(0, len));
        int end = Math.min(off + len, b.length);
        for (int i = off; i < end; i++) {
            int v = b[i] & 0xFF;
            if (v >= 0xF0 && v <= 0xF9) sb.append((char) ('0' + (v - 0xF0)));
            else sb.append(decode(b, i, 1));
        }
        return sb.toString();
    }

    /** True if the range is all zoned digits (a valid LLVAR/LLLVAR length prefix). */
    public static boolean isDigits(byte[] b, int off, int len) {
        int end = Math.min(off + len, b.length);
        if (end - off < len) return false;
        for (int i = off; i < end; i++) {
            int v = b[i] & 0xFF;
            if (v < 0xF0 || v > 0xF9) return false;
        }
        return true;
    }
}
