package com.acquira.batch.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the "everything peaks at 00:00" bug.
 *
 * The AFS Bahrain export carries a separate "Transaction Date" column
 * ("10-JUN-25") AND a "Transaction DateTime" column that, despite its name,
 * holds only the clock time ("19:06:14"). The splitter used to funnel
 * "Transaction DateTime" through the "transaction date" alias; because the real
 * "Transaction Date" column already held that slot, the alias was suppressed,
 * the DATE-only column got split, no time was found, and every row landed at
 * 00:00 -- so the PDF peak-hour cards and day x hour heatmap reported 00:00 for
 * every merchant.
 *
 * After the fix the time-only column must be routed into "Transaction Time".
 */
class ExcelSplitterDateTimeTest {

    // Column layout of the sample AFS Bahrain file.
    private static final String HEADER =
            "Entity Name,Payment Date,MID,SID,TerminalID,CardNumber,Transaction Type,"
          + "CardScheme,Card Type,Destination,Store Base Currency,Store Base Currency Amount,"
          + "MSF,Txn Currency,Txn Currency Amount,Transaction Date,Transaction DateTime,VAT,DCC";

    private static final String ROW =
            "AFSB,11-JUN-25,000000000014647,000000000014696,01630144,441555******8870,Purchase,"
          + "Benefit,,Local,048,12.815,-.16,048,12.815,10-JUN-25,19:06:14,-0.016,0";

    @TempDir
    Path tempDir;

    /** Target header index of "Transaction Date" / "Transaction Time" in the split output. */
    private static final int OUT_TXN_DATE = 18;
    private static final int OUT_TXN_TIME = 19;

    @Test
    void timeOnlyDateTimeColumnPopulatesTransactionTime() throws Exception {
        File csv = tempDir.resolve("afs_bahrain_sample.csv").toFile();
        Files.write(csv.toPath(), (HEADER + "\n" + ROW + "\n").getBytes(StandardCharsets.UTF_8));

        ExcelSplitterTasklet tasklet = new ExcelSplitterTasklet();
        Method split = ExcelSplitterTasklet.class.getDeclaredMethod("splitCsvFile", File.class, Path.class);
        split.setAccessible(true);
        Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);
        long rows = (long) split.invoke(tasklet, csv, outDir);
        assertEquals(1L, rows, "the one data row should be split");

        Path out;
        try (var s = Files.list(outDir)) {
            out = s.filter(p -> p.toString().endsWith(".csv")).findFirst().orElseThrow();
        }
        List<String> lines = Files.readAllLines(out);
        String[] cells = parseCsv(lines.get(1));

        assertEquals("10-JUN-25", cells[OUT_TXN_DATE], "date must come from the real Transaction Date column");
        assertEquals("19:06:14", cells[OUT_TXN_TIME], "time must be carried over from the Transaction DateTime column");
    }

    /** Split a double-quoted CSV line into raw cell values. */
    private static String[] parseCsv(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
