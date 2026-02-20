package com.acquira.batch.job;

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
 * OPTIMIZATIONS:
 * 1. BufferedWriter with 256KB buffer (vs default 8KB)
 * 2. StringBuilder reuse (single allocation per row)
 * 3. Pre-computed header index array (no HashMap lookup per cell)
 * 4. Larger chunk size: 50K rows/file (fewer file handles, better I/O)
 * 5. fastexcel streaming reader for XLSX (no DOM loading)
 * 6. CSV passthrough: stream-split without Excel parsing
 *
 * For 1.5GB CSV files: pure streaming, ~0 memory overhead.
 * For 1.5GB XLSX files: fastexcel SAX-based streaming, ~50MB overhead.
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

    private static final int CHUNK_SIZE = 50_000;
    private static final int BUFFER_SIZE = 256 * 1024; // 256KB

    /** Normalize header: trim, lowercase, underscores→spaces, collapse whitespace */
    private static String normalizeHeader(String h) {
        return h.trim().toLowerCase().replace('_', ' ').replaceAll("\\s+", " ");
    }

    /** Alias map: alternative Excel header names → our TARGET_HEADER (all normalized) */
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
        HEADER_ALIASES.put("transaction time",               "transaction time"); // now a target column
    }

    /**
     * Build source-index array by matching TARGET_HEADERS against the source headerMap.
     * Uses normalized names + alias fallback.
     */
    private static int[] buildSourceIndexes(Map<String, Integer> normalizedHeaderMap) {
        // Expand headerMap with aliases: if source has "original currency", also register as "txn currency"
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

    // ==================================================================================
    // CSV SPLITTER — pure streaming, handles any file size with ~0 memory
    // ==================================================================================
    private long splitCsvFile(File inputFile, Path outputDir) throws Exception {
        long startTime = System.currentTimeMillis();
        long totalRows = 0;

        // Detect delimiter from first line
        String firstLine;
        try (BufferedReader peek = new BufferedReader(new FileReader(inputFile))) {
            firstLine = peek.readLine();
        }
        if (firstLine == null) return 0;

        char delimiter = firstLine.contains("\t") ? '\t' : ',';

        // Parse header and build column mapping
        String[] sourceHeaders = parseCsvLine(firstLine, delimiter);
        Map<String, Integer> headerMap = new HashMap<>();
        for (int i = 0; i < sourceHeaders.length; i++) {
            headerMap.put(normalizeHeader(sourceHeaders[i]), i);
        }
        int[] sourceIndexes = buildSourceIndexes(headerMap);

        // Build CSV header
        StringBuilder headerSb = new StringBuilder(512);
        for (int i = 0; i < TARGET_HEADERS.length; i++) {
            if (i > 0) headerSb.append(',');
            headerSb.append('"').append(TARGET_HEADERS[i]).append('"');
        }
        String headerLine = headerSb.toString();

        // Check if source matches target exactly (common case — skip remapping)
        boolean directCopy = (sourceHeaders.length == TARGET_HEADERS.length);
        if (directCopy) {
            for (int i = 0; i < TARGET_HEADERS.length; i++) {
                if (!TARGET_HEADERS[i].equals(sourceHeaders[i].trim())) {
                    directCopy = false;
                    break;
                }
            }
        }

        // Stream and split
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile), BUFFER_SIZE)) {
            reader.readLine(); // skip header (already read)

            int fileIndex = 1;
            int rowCount = 0;
            BufferedWriter writer = createWriter(outputDir, fileIndex, headerLine);
            StringBuilder sb = new StringBuilder(2048);
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                if (directCopy) {
                    // Headers match exactly — write line as-is (fastest path)
                    writer.write(line);
                } else {
                    // Remap columns to target order
                    String[] fields = parseCsvLine(line, delimiter);
                    sb.setLength(0);
                    for (int i = 0; i < TARGET_HEADERS.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append('"');
                        int srcIdx = sourceIndexes[i];
                        if (srcIdx >= 0 && srcIdx < fields.length) {
                            String val = fields[srcIdx];
                            if (val != null && !val.isEmpty()) {
                                for (int c = 0; c < val.length(); c++) {
                                    char ch = val.charAt(c);
                                    if (ch == '"') sb.append('"');
                                    sb.append(ch);
                                }
                            }
                        }
                        sb.append('"');
                    }
                    writer.write(sb.toString());
                }
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
        }

        return totalRows;
    }

    /**
     * Parse a single CSV line respecting quoted fields.
     * Handles: "field with, comma", "field with ""quote""", simple_field
     */
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
                        i++; // skip escaped quote
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
    // EXCEL SPLITTER — fastexcel streaming (existing logic)
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

                // Read header and build INDEX ARRAY
                Row headerRow = iterator.next();
                Map<String, Integer> headerMap = new HashMap<>();
                int cellCount = headerRow.getCellCount();
                for (int i = 0; i < cellCount; i++) {
                    String val = headerRow.getCell(i).getText();
                    if (val != null) headerMap.put(normalizeHeader(val), i);
                }
                int[] sourceIndexes = buildSourceIndexes(headerMap);

                // Build CSV header string once
                StringBuilder headerSb = new StringBuilder(512);
                for (int i = 0; i < TARGET_HEADERS.length; i++) {
                    if (i > 0) headerSb.append(',');
                    headerSb.append('"').append(TARGET_HEADERS[i]).append('"');
                }
                String headerLine = headerSb.toString();

                // Stream rows into chunked CSV files
                int fileIndex = 1;
                int rowCount = 0;
                BufferedWriter writer = createWriter(outputDir, fileIndex, headerLine);
                StringBuilder sb = new StringBuilder(2048);

                while (iterator.hasNext()) {
                    Row row = iterator.next();
                    int maxCell = row.getCellCount();

                    sb.setLength(0);
                    for (int i = 0; i < TARGET_HEADERS.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append('"');

                        int srcIdx = sourceIndexes[i];
                        if (srcIdx >= 0 && srcIdx < maxCell) {
                            String val = row.getCell(srcIdx).getText();
                            // Fallback: getText() returns empty for date/numeric cells
                            // stored as Excel serial numbers — use raw value instead
                            if (val == null || val.isEmpty()) {
                                val = row.getCell(srcIdx).getRawValue();
                            }
                            if (val != null && !val.isEmpty()) {
                                for (int c = 0; c < val.length(); c++) {
                                    char ch = val.charAt(c);
                                    if (ch == '"') sb.append('"');
                                    sb.append(ch);
                                }
                            }
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
