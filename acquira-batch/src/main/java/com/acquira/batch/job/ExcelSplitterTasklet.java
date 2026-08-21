package com.acquira.batch.job;

import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * HIGH-PERFORMANCE File Splitter — handles Excel (.xlsx) and CSV files
 *
 * FIXES APPLIED:
 *  - Null-safe cell access (handles sparse rows + missing columns)
 *  - "Transaction DateTime" auto-split into Transaction Date + Transaction Time
 *  - VAT column gracefully handled when absent in source file
 */
@Component
@Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class ExcelSplitterTasklet implements Tasklet {

    @Value("#{jobParameters['fullPath']}")
    private String fullPath;

    private static final String[] TARGET_HEADERS = {
            "Entity Name", "Aggregator Internal Id", "Aggregator Name", "AggregatorCode",
            "MID", "Merchant Internal Id", "Merchant Name",
            "SID", "Merchant Store Internal Id", "CMM Merchant Store Internal Id",
            "Merchant Store Legal Name", "Store Name",
            "TerminalID", "ARN", "RRN Number", "CardNumber", "Auth Code",
            "Payment Date", "Transaction Date", "Transaction Time", "BatchNumber", "Transaction Type", "CardScheme",
            "Card Type", "DCC",
            "Txn Currency", "Txn Currency Amount", "Store Base Currency",
            "Store Base Currency Amount",
            "MSF", "VAT", "Total Amount Settled", "Interchange Fee", "Destination"
    };

    // Indexes of target columns that need special handling
    private static final int IDX_TRANSACTION_DATE = 18;
    private static final int IDX_TRANSACTION_TIME = 19;

    /**
     * Money columns, by index into TARGET_HEADERS: Txn Currency Amount (26),
     * Store Base Currency Amount (28), MSF (29), VAT (30), Total Amount
     * Settled (31), Interchange Fee (32).
     *
     * These MUST be read as the cell's underlying number, never as its
     * displayed text — see safeMoneyText(). Everything else (dates, ids,
     * codes) still goes through safeCellText(), which needs the formatted
     * form: a date cell's raw value is an Excel serial number, not a date.
     */
    private static final boolean[] IS_MONEY_COL = new boolean[TARGET_HEADERS.length];
    static {
        for (int i : new int[]{26, 28, 29, 30, 31, 32}) IS_MONEY_COL[i] = true;
    }

    private static final int CHUNK_SIZE = 50_000;
    private static final int BUFFER_SIZE = 256 * 1024; // 256KB

    /** Normalize header: trim, lowercase, underscores->spaces, collapse whitespace */
    private static String normalizeHeader(String h) {
        if (h == null) return "";
        return h.trim().toLowerCase().replace('_', ' ').replaceAll("\\s+", " ");
    }

    /** Alias map: alternative Excel header names -> our TARGET_HEADER (all normalized) */
    private static final Map<String, String> HEADER_ALIASES = new HashMap<>();
    static {
        HEADER_ALIASES.put("aggregator code",                "aggregatorcode");
        HEADER_ALIASES.put("original currency",              "txn currency");
        HEADER_ALIASES.put("original base currency amount",  "txn currency amount");
        HEADER_ALIASES.put("settled currency",               "store base currency");
        HEADER_ALIASES.put("msf and flat fee",               "msf");
        HEADER_ALIASES.put("tran amount settled",            "total amount settled");
        HEADER_ALIASES.put("card destination",               "destination");
        HEADER_ALIASES.put("card type acq",                  "card type");
        HEADER_ALIASES.put("dcc txn ind",                    "dcc");
        // Combined datetime column maps to Transaction Date target slot;
        // the time portion is auto-split at write time (see splitDateTime()).
        HEADER_ALIASES.put("transaction datetime",           "transaction date");
    }

    /**
     * Build source-index array by matching TARGET_HEADERS against the source headerMap.
     * Uses normalized names + alias fallback.
     */
    private static int[] buildSourceIndexes(Map<String, Integer> normalizedHeaderMap) {
        Map<String, Integer> expanded = new HashMap<>(normalizedHeaderMap);
        for (Map.Entry<String, Integer> entry : normalizedHeaderMap.entrySet()) {
            String alias = HEADER_ALIASES.get(entry.getKey());
            if (alias != null && !expanded.containsKey(alias)) {
                expanded.put(alias, entry.getValue());
            }
        }
        int[] sourceIndexes = new int[TARGET_HEADERS.length];
        for (int i = 0; i < TARGET_HEADERS.length; i++) {
            Integer idx = expanded.get(normalizeHeader(TARGET_HEADERS[i]));
            sourceIndexes[i] = (idx != null) ? idx : -1;
        }
        return sourceIndexes;
    }

    /**
     * Check if the source uses a combined "Transaction DateTime" column.
     */
    private static boolean hasCombinedDateTime(Map<String, Integer> normalizedHeaderMap) {
        return normalizedHeaderMap.containsKey("transaction datetime")
                && !normalizedHeaderMap.containsKey("transaction time");
    }

    /**
     * Split a combined datetime string into [datePart, timePart].
     */
    private static String[] splitDateTime(String raw) {
        if (raw == null || raw.isEmpty()) return new String[]{"", ""};
        String v = raw.trim();

        // Excel serial number (e.g., "45716.847742")
        if (v.matches("-?\\d+(\\.\\d+)?")) {
            try {
                double serial = Double.parseDouble(v);
                long days = (long) serial;
                double fraction = serial - days;
                String dateStr = Long.toString(days);
                if (fraction <= 0) return new String[]{v, ""};
                long totalSecs = Math.round(fraction * 86400);
                long hh = totalSecs / 3600;
                long mm = (totalSecs % 3600) / 60;
                long ss = totalSecs % 60;
                String timeStr = String.format("%02d:%02d:%02d", hh, mm, ss);
                return new String[]{dateStr, timeStr};
            } catch (NumberFormatException ignored) {
                return new String[]{v, ""};
            }
        }

        int tIdx = v.indexOf('T');
        if (tIdx > 0 && tIdx < v.length() - 1) {
            return new String[]{v.substring(0, tIdx), v.substring(tIdx + 1)};
        }

        int spaceIdx = v.indexOf(' ');
        if (spaceIdx > 0 && spaceIdx < v.length() - 1) {
            String date = v.substring(0, spaceIdx);
            String time = v.substring(spaceIdx + 1);
            int dot = time.indexOf('.');
            if (dot > 0) time = time.substring(0, dot);
            return new String[]{date, time};
        }

        return new String[]{v, ""};
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        Assert.notNull(fullPath, "Input file path must not be null");
        File inputFile = new File(fullPath);
        if (!inputFile.exists()) {
            throw new IllegalStateException("Input file does not exist: " + fullPath);
        }

        String jobId = chunkContext.getStepContext().getStepExecution().getJobExecution().getId().toString();
        Path outputDir = Paths.get("temp", "job_" + jobId);
        Files.createDirectories(outputDir);

        chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext()
                .put("partitionDirectory", outputDir.toAbsolutePath().toString());

        String lowerName = inputFile.getName().toLowerCase();
        long totalRows;

        if (lowerName.endsWith(".csv") || lowerName.endsWith(".tsv") || lowerName.endsWith(".txt")) {
            totalRows = splitCsvFile(inputFile, outputDir);
        } else {
            totalRows = splitExcelFile(inputFile, outputDir);
        }

        chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext()
                .putLong("totalReqRows", totalRows);

        return RepeatStatus.FINISHED;
    }

    /** Safely read a cell's text - never throws NPE. */
    private static String safeCellText(Row row, int idx, int maxCell) {
        if (idx < 0 || idx >= maxCell) return "";
        Cell cell = row.getCell(idx);
        if (cell == null) return "";
        String val = cell.getText();
        if (val == null || val.isEmpty()) {
            val = cell.getRawValue();
        }
        return val != null ? val : "";
    }

    /**
     * Read a MONEY cell at full stored precision.
     *
     * getText() returns what Excel DISPLAYS, which applies the cell's number
     * format. A cell holding 12.3456 but formatted to 2 decimals returns
     * "12.35" — so a workbook that merely displays 2 dp silently rounded every
     * amount at the very first step of ingestion, before staging and before
     * fact_transaction. MSF is the visible casualty because, unlike volume, it
     * is never re-derived downstream: whatever lands here IS the number every
     * report sums. getRawValue() returns the underlying stored value verbatim.
     *
     * Falls back to getText() for non-numeric cells (a text-typed amount column
     * has no raw numeric form) so a malformed sheet degrades rather than blanks.
     */
    private static String safeMoneyText(Row row, int idx, int maxCell) {
        if (idx < 0 || idx >= maxCell) return "";
        Cell cell = row.getCell(idx);
        if (cell == null) return "";
        String raw = cell.getRawValue();
        if (raw != null && !raw.isEmpty()) return raw;
        String val = cell.getText();
        return val != null ? val : "";
    }

    // ==================================================================================
    // CSV SPLITTER
    // ==================================================================================
    private long splitCsvFile(File inputFile, Path outputDir) throws Exception {
        long startTime = System.currentTimeMillis();
        long totalRows = 0;

        String firstLine;
        try (BufferedReader peek = new BufferedReader(new FileReader(inputFile))) {
            firstLine = peek.readLine();
        }
        if (firstLine == null) return 0;

        char delimiter = firstLine.contains("\t") ? '\t' : ',';

        String[] sourceHeaders = parseCsvLine(firstLine, delimiter);
        Map<String, Integer> headerMap = new HashMap<>();
        for (int i = 0; i < sourceHeaders.length; i++) {
            headerMap.put(normalizeHeader(sourceHeaders[i]), i);
        }
        int[] sourceIndexes = buildSourceIndexes(headerMap);
        boolean combinedDateTime = hasCombinedDateTime(headerMap);

        System.out.println("=== CSV HEADER MAPPING ===");
        System.out.println("Source headers found: " + headerMap.keySet());
        System.out.println("Combined DateTime mode: " + combinedDateTime);
        for (int i = 0; i < TARGET_HEADERS.length; i++) {
            String status = sourceIndexes[i] >= 0 ? "MAPPED (col " + sourceIndexes[i] + ")" : "*** MISSING (will be empty) ***";
            System.out.printf("  [%d] %-30s -> %s%n", i, TARGET_HEADERS[i], status);
        }

        StringBuilder headerSb = new StringBuilder(512);
        for (int i = 0; i < TARGET_HEADERS.length; i++) {
            if (i > 0) headerSb.append(',');
            headerSb.append('"').append(TARGET_HEADERS[i]).append('"');
        }
        String headerLine = headerSb.toString();

        // Pre-normalize the source header names once, so embedded duplicate
        // header lines (concatenated monthly exports) can be recognized per row.
        String[] normalizedSourceHeaders = new String[sourceHeaders.length];
        for (int i = 0; i < sourceHeaders.length; i++) {
            normalizedSourceHeaders[i] = normalizeHeader(sourceHeaders[i]);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile), BUFFER_SIZE)) {
            reader.readLine();

            int fileIndex = 1;
            int rowCount = 0;
            long skippedHeaderRows = 0;
            BufferedWriter writer = createWriter(outputDir, fileIndex, headerLine);
            StringBuilder sb = new StringBuilder(2048);
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] fields = parseCsvLine(line, delimiter);

                // Files produced by concatenating several exports repeat the
                // header line mid-file. Such a line would otherwise be written
                // out as DATA ('Txn Currency' -> txn_currency VARCHAR(10) ->
                // "value too long" aborts the whole staging insert), so drop it.
                if (isEmbeddedHeaderLine(fields, normalizedSourceHeaders)) {
                    skippedHeaderRows++;
                    continue;
                }

                String splitDate = null, splitTime = null;
                if (combinedDateTime) {
                    int dtIdx = sourceIndexes[IDX_TRANSACTION_DATE];
                    if (dtIdx >= 0 && dtIdx < fields.length) {
                        String[] parts = splitDateTime(fields[dtIdx]);
                        splitDate = parts[0];
                        splitTime = parts[1];
                    }
                }

                sb.setLength(0);
                for (int i = 0; i < TARGET_HEADERS.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"');

                    String val = "";
                    if (combinedDateTime && i == IDX_TRANSACTION_DATE && splitDate != null) {
                        val = splitDate;
                    } else if (combinedDateTime && i == IDX_TRANSACTION_TIME && splitTime != null) {
                        val = splitTime;
                    } else {
                        int srcIdx = sourceIndexes[i];
                        if (srcIdx >= 0 && srcIdx < fields.length) {
                            val = fields[srcIdx] != null ? fields[srcIdx] : "";
                        }
                    }

                    for (int c = 0; c < val.length(); c++) {
                        char ch = val.charAt(c);
                        if (ch == '"') sb.append('"');
                        sb.append(ch);
                    }
                    sb.append('"');
                }
                writer.write(sb.toString());
                writer.newLine();
                rowCount++;
                totalRows++;

                if (rowCount >= CHUNK_SIZE) {
                    writer.close();
                    fileIndex++;
                    rowCount = 0;
                    writer = createWriter(outputDir, fileIndex, headerLine);
                }
            }

            if (writer != null) writer.close();

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("CSV split complete: %,d rows -> %d files in %.1fs (%,.0f rows/sec)%n",
                    totalRows, fileIndex, elapsed / 1000.0, totalRows * 1000.0 / Math.max(elapsed, 1));
            if (skippedHeaderRows > 0) {
                System.out.printf("CSV split: skipped %,d embedded duplicate header line(s)%n", skippedHeaderRows);
            }
        }

        return totalRows;
    }

    /**
     * A line is a repeated header if every field it has matches the source
     * header at the same position (normalized). Requires the full header width
     * so a data row that merely starts with a header-like word never matches.
     */
    private static boolean isEmbeddedHeaderLine(String[] fields, String[] normalizedSourceHeaders) {
        if (fields.length != normalizedSourceHeaders.length) return false;
        for (int i = 0; i < fields.length; i++) {
            if (!normalizeHeader(fields[i]).equals(normalizedSourceHeaders[i])) return false;
        }
        return true;
    }

    private String[] parseCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == delimiter) {
                    fields.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    // ==================================================================================
    // EXCEL SPLITTER - NULL-SAFE
    // ==================================================================================
    private long splitExcelFile(File inputFile, Path outputDir) throws Exception {
        long startTime = System.currentTimeMillis();
        long totalRows = 0;

        try (InputStream is = new BufferedInputStream(new FileInputStream(inputFile), BUFFER_SIZE);
                ReadableWorkbook wb = new ReadableWorkbook(is)) {

            Sheet sheet = wb.getFirstSheet();
            try (Stream<Row> rows = sheet.openStream()) {
                Iterator<Row> iterator = rows.iterator();

                if (!iterator.hasNext()) return 0;

                Row headerRow = iterator.next();
                Map<String, Integer> headerMap = new HashMap<>();
                int cellCount = headerRow.getCellCount();
                for (int i = 0; i < cellCount; i++) {
                    Cell hc = headerRow.getCell(i);
                    if (hc == null) continue;
                    String val = hc.getText();
                    if (val != null && !val.isEmpty()) {
                        headerMap.put(normalizeHeader(val), i);
                    }
                }
                int[] sourceIndexes = buildSourceIndexes(headerMap);
                boolean combinedDateTime = hasCombinedDateTime(headerMap);

                System.out.println("=== EXCEL HEADER MAPPING ===");
                System.out.println("Source headers found: " + headerMap.keySet());
                System.out.println("Combined DateTime mode: " + combinedDateTime);
                for (int i = 0; i < TARGET_HEADERS.length; i++) {
                    String status = sourceIndexes[i] >= 0 ? "MAPPED (col " + sourceIndexes[i] + ")" : "*** MISSING (will be empty) ***";
                    System.out.printf("  [%d] %-30s -> %s%n", i, TARGET_HEADERS[i], status);
                }

                StringBuilder headerSb = new StringBuilder(512);
                for (int i = 0; i < TARGET_HEADERS.length; i++) {
                    if (i > 0) headerSb.append(',');
                    headerSb.append('"').append(TARGET_HEADERS[i]).append('"');
                }
                String headerLine = headerSb.toString();

                int fileIndex = 1;
                int rowCount = 0;
                BufferedWriter writer = createWriter(outputDir, fileIndex, headerLine);
                StringBuilder sb = new StringBuilder(2048);

                while (iterator.hasNext()) {
                    Row row = iterator.next();
                    int maxCell = row.getCellCount();

                    String splitDate = null, splitTime = null;
                    if (combinedDateTime) {
                        int dtIdx = sourceIndexes[IDX_TRANSACTION_DATE];
                        String rawDt = safeCellText(row, dtIdx, maxCell);
                        String[] parts = splitDateTime(rawDt);
                        splitDate = parts[0];
                        splitTime = parts[1];
                    }

                    sb.setLength(0);
                    for (int i = 0; i < TARGET_HEADERS.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append('"');

                        String val;
                        if (combinedDateTime && i == IDX_TRANSACTION_DATE) {
                            val = splitDate != null ? splitDate : "";
                        } else if (combinedDateTime && i == IDX_TRANSACTION_TIME) {
                            val = splitTime != null ? splitTime : "";
                        } else if (IS_MONEY_COL[i]) {
                            val = safeMoneyText(row, sourceIndexes[i], maxCell);
                        } else {
                            val = safeCellText(row, sourceIndexes[i], maxCell);
                        }

                        for (int c = 0; c < val.length(); c++) {
                            char ch = val.charAt(c);
                            if (ch == '"') sb.append('"');
                            sb.append(ch);
                        }
                        sb.append('"');
                    }

                    writer.write(sb.toString());
                    writer.newLine();
                    rowCount++;
                    totalRows++;

                    if (rowCount >= CHUNK_SIZE) {
                        writer.close();
                        fileIndex++;
                        rowCount = 0;
                        writer = createWriter(outputDir, fileIndex, headerLine);
                    }
                }

                if (writer != null) writer.close();

                long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("Excel split complete: %,d rows -> %d files in %.1fs (%,.0f rows/sec)%n",
                        totalRows, fileIndex, elapsed / 1000.0, totalRows * 1000.0 / Math.max(elapsed, 1));
            }
        }

        return totalRows;
    }

    private BufferedWriter createWriter(Path dir, int index, String header) throws Exception {
        File f = dir.resolve("part_" + String.format("%03d", index) + ".csv").toFile();
        BufferedWriter bw = new BufferedWriter(new FileWriter(f), BUFFER_SIZE);
        bw.write(header);
        bw.newLine();
        return bw;
    }
}
