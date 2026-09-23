package com.acquira.common.interchange;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.acquira.common.interchange.Violation.Category.*;
import static com.acquira.common.interchange.Violation.Severity.*;

/**
 * Layer-1 data-integrity validator for parsed Mastercard IPM files.
 *
 *   STRUCTURAL — known MTI, File Header (1644) first, File Trailer (1644) last,
 *                trailer message count matches, partial-decode flags.
 *   REQUIRED   — the core mandatory DE set on each First Presentment (1240).
 *
 * Full per-message M/C/O and PDS edits come once the applicable-messages tables
 * are extracted from the DMC guide; this is the v1 core.
 */
@Component
public class IpmValidator {

    private static final Map<String, String> KNOWN_MTI = Map.of(
            "1240", "First/Second Presentment",
            "1442", "Chargeback",
            "1740", "Fee Collection",
            "1644", "Administrative (File Header/Trailer)");

    /** Core DEs a 1240 presentment must carry. */
    private static final Set<Integer> REQUIRED_1240 = Set.of(2, 3, 4, 12, 24, 26, 49);

    private final IpmLayouts layouts;
    private final DimpEngine dimp;

    public IpmValidator(IpmLayouts layouts, DimpEngine dimp) {
        this.layouts = layouts;
        this.dimp = dimp;
    }

    public List<Violation> validate(ParsedIpmFile pf) {
        List<Violation> out = new ArrayList<>();

        // Envelope.
        if (pf.messages.isEmpty()) {
            out.add(Violation.of(STRUCTURAL, ERROR, "EMPTY_FILE", "No IPM messages found in file"));
            return out;
        }
        if (pf.header == null || !"1644".equals(pf.messages.get(0).mti)) {
            out.add(Violation.of(STRUCTURAL, ERROR, "MISSING_FILE_HEADER",
                    "File does not begin with a 1644 File Header"));
        }
        IpmMessage last = pf.messages.get(pf.messages.size() - 1);
        if (!"1644".equals(last.mti)) {
            out.add(Violation.of(STRUCTURAL, ERROR, "MISSING_FILE_TRAILER",
                    "File does not end with a 1644 File Trailer"));
        }

        // Trailer message number vs actual count (DE 71 on the trailer).
        if (pf.trailer != null && pf.trailer.has(71)) {
            long declared = parseLong(pf.trailer.get(71));
            if (declared != pf.recordCount) {
                out.add(Violation.of(STRUCTURAL, ERROR, "TRAILER_COUNT",
                                "File Trailer message number " + declared + " != actual record count " + pf.recordCount)
                        .record(pf.trailer.recordNo, pf.trailer.mti, null)
                        .values(String.valueOf(pf.recordCount), String.valueOf(declared)));
            }
        }

        // Per message.
        for (IpmMessage m : pf.messages) {
            if (!KNOWN_MTI.containsKey(m.mti)) {
                out.add(Violation.of(STRUCTURAL, WARN, "UNKNOWN_MTI", "Unrecognised MTI '" + m.mti + "'")
                        .record(m.recordNo, m.mti, null).txn(m.recordNo));
            }
            if (!m.fullyDecoded) {
                String note = m.notes.isEmpty() ? "message did not fully decode" : m.notes.get(0);
                out.add(Violation.of(FIELD_EDIT, WARN, "PARTIAL_DECODE", note)
                        .record(m.recordNo, m.mti, null).txn(m.recordNo));
                continue; // missing DEs here may be misalignment, not real absence
            }
            if ("1240".equals(m.mti)) {
                for (int de : REQUIRED_1240) {
                    if (!m.has(de)) {
                        out.add(Violation.of(REQUIRED, ERROR, "REQUIRED_DE_MISSING",
                                        "Mandatory DE " + de + " (" + layouts.deName(de) + ") missing on presentment")
                                .record(m.recordNo, m.mti, null).field("DE " + de, de, de, "M").txn(m.recordNo));
                    }
                }
                // DIMP Acquirer Clearing edits (file-only; MATCH edits need the auth feed).
                out.addAll(dimp.evaluate(m));
            }
        }
        return out;
    }

    private long parseLong(String s) {
        if (s == null) return -1;
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? -1 : Long.parseLong(d);
    }
}
