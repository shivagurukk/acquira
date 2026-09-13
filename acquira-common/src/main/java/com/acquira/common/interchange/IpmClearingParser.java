package com.acquira.common.interchange;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Mastercard IPM (Integrated Product Message) clearing file into
 * {@link ParsedIpmFile}. Framing (validated against a live file):
 *
 *   • 1014-byte blocking: when the file length is a multiple of 1014, each block
 *     is 1012 data bytes + a 2-byte terminator — de-block first.
 *   • Record stream: each record is a 4-byte big-endian length (excluding
 *     itself) followed by an EBCDIC IPM message.
 *   • Message: MTI (4 zoned digits) + primary bitmap (8 bytes) + optional
 *     secondary bitmap (8 bytes, when DE 1 bit is set) + data elements in order.
 *   • Each DE is fixed length or LLVAR/LLLVAR (a 2/3 zoned-digit length prefix),
 *     per {@link IpmLayouts}.
 *
 * Resilient by design: if a DE walk misaligns (a variable-length prefix is not
 * numeric, or an unknown DE has no length), it records a note, marks the message
 * partially decoded, and stops that message's DE loop — keeping the DEs already
 * decoded rather than throwing. PANs are masked (first6..last4).
 */
@Component
public class IpmClearingParser {

    private static final int BLOCK = 1014;
    private static final int BLOCK_DATA = 1012;

    private final IpmLayouts layouts;

    public IpmClearingParser(IpmLayouts layouts) { this.layouts = layouts; }

    public ParsedIpmFile parse(byte[] raw, String fileName, String direction) {
        ParsedIpmFile pf = new ParsedIpmFile();
        pf.fileName = fileName;
        pf.direction = direction == null ? "INCOMING" : direction.toUpperCase();

        byte[] buf = deblock(raw, pf);

        long recNo = 0;
        int o = 0;
        while (o + 4 <= buf.length) {
            long len = readU32(buf, o);
            if (len < 8 || o + 4 + len > buf.length) break; // trailing padding / end
            byte[] body = slice(buf, o + 4, (int) len);
            IpmMessage m = decodeMessage(body, ++recNo);
            pf.messages.add(m);
            if ("1644".equals(m.mti)) { if (pf.header == null) pf.header = m; pf.trailer = m; }
            o += 4 + (int) len;
        }
        pf.recordCount = pf.messages.size();
        return pf;
    }

    // ── Framing ────────────────────────────────────────────────────────────────

    private byte[] deblock(byte[] raw, ParsedIpmFile pf) {
        if (raw == null) return new byte[0];
        if (raw.length == 0 || raw.length % BLOCK != 0) return raw;
        // Heuristic: only treat as blocked if the last 2 bytes of the first block
        // are a 0x0000 terminator (the observed marker); else leave as raw.
        if (!(raw[BLOCK_DATA] == 0 && raw[BLOCK_DATA + 1] == 0)) return raw;
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int o = 0; o < raw.length; o += BLOCK) out.write(raw, o, BLOCK_DATA);
        pf.blocked = true;
        return out.toByteArray();
    }

    // ── One message ────────────────────────────────────────────────────────────

    private IpmMessage decodeMessage(byte[] body, long recNo) {
        IpmMessage m = new IpmMessage();
        m.recordNo = recNo;
        m.mti = Ebcdic.digits(body, 0, 4);

        List<Integer> des = bitmapDes(body, 4, 1);
        int p = 12;
        if (des.contains(1)) { des.addAll(bitmapDes(body, 12, 65)); p = 20; }
        des.remove(Integer.valueOf(1));
        des.sort(Integer::compareTo);

        for (int de : des) {
            IpmLayouts.DeSpec s = layouts.de(de);
            if (s == null) {
                m.fullyDecoded = false;
                m.notes.add("DE " + de + ": unknown data element (no length) — stopped decode at record offset " + p);
                break;
            }
            int len, dataStart;
            if (s.variable) {
                if (!Ebcdic.isDigits(body, p, s.prefixDigits)) {
                    m.fullyDecoded = false;
                    m.notes.add("DE " + de + " (" + s.name + "): variable-length prefix not numeric at offset " + p + " — misaligned, stopped");
                    break;
                }
                len = Integer.parseInt(Ebcdic.digits(body, p, s.prefixDigits));
                dataStart = p + s.prefixDigits;
            } else {
                len = s.fixedLen;
                if (len <= 0) {
                    m.fullyDecoded = false;
                    m.notes.add("DE " + de + " (" + s.name + "): no fixed length known — stopped");
                    break;
                }
                dataStart = p;
            }
            if (dataStart + len > body.length) {
                m.fullyDecoded = false;
                m.notes.add("DE " + de + " (" + s.name + "): declared length " + len + " runs past record end — stopped");
                break;
            }
            m.de.put(de, decodeValue(de, s, body, dataStart, len));
            p = dataStart + len;
        }
        return m;
    }

    private String decodeValue(int de, IpmLayouts.DeSpec s, byte[] body, int off, int len) {
        if (de == 2) return maskPan(Ebcdic.digits(body, off, len));       // PAN
        if ("b".equalsIgnoreCase(s.format)) return "0x" + hex(body, off, Math.min(len, 24)) + (len > 24 ? "…" : "");
        return Ebcdic.decode(body, off, len).stripTrailing();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private List<Integer> bitmapDes(byte[] b, int off, int base) {
        List<Integer> des = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            int idx = off + (i / 8);
            if (idx >= b.length) break;
            if ((b[idx] & (0x80 >> (i % 8))) != 0) des.add(base + i);
        }
        return des;
    }

    private long readU32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private byte[] slice(byte[] b, int off, int len) {
        int end = Math.min(off + len, b.length);
        byte[] out = new byte[Math.max(0, end - off)];
        System.arraycopy(b, off, out, 0, out.length);
        return out;
    }

    private String maskPan(String pan) {
        String d = pan == null ? "" : pan.replaceAll("\\D", "");
        if (d.length() < 10) return d.isEmpty() ? null : "*".repeat(d.length());
        return d.substring(0, 6) + "*".repeat(d.length() - 10) + d.substring(d.length() - 4);
    }

    private String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(off + len, b.length);
        for (int i = off; i < end; i++) sb.append(String.format("%02x", b[i] & 0xFF));
        return sb.toString();
    }
}
