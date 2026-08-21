package com.acquira.batch;

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
 * HIGH-PERFORMANCE Excel Splitter — handles 999K rows
 *
 * OPTIMIZATIONS:
 * 1. BufferedWriter with 256KB buffer (vs default 8KB)
 * 2. StringBuilder reuse (single allocation per row)
 * 3. Pre-computed header index array (no HashMap lookup per cell)
 * 4. Larger chunk size: 50K rows/file (fewer file handles, better I/O)
 * 5. fastexcel streaming reader (already in place — no DOM loading)
 */
@Component
@Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class ExcelSplitterTasklet implements Tasklet {

    @Value("#{jobParameters['fullPath']}")
    private String fullPath;

    /**
     * OUTPUT CSV column names — these are the canonical names used by the CSV reader.
     * The alias map below handles all known Excel header variations.
     */
    private static final String[] TARGET_HEADERS = {
            "Entity Name", "Aggregator Internal Id", "Aggregator Name", "AggregatorCode",
            "MID", "Merchant Internal Id", "Merchant Name",
            "SID", "Merchant Store Internal Id", "CMM Merchant Store Internal Id",
            "Merchant Store Legal Name", "Store Name",
            "TerminalID", "ARN", "RRN Number", "CardNumber", "Auth Code",
            "Payment Date", "Transaction Date", "BatchNumber", "Transaction Type", "CardScheme",
            "Card Type", "DCC",
            "Txn Currency", "Txn Currency Amount", "Store Base Currency",
            "Store Base Currency Amount",
            "MSF", "VAT", "Total Amount Settled", "Interchange Fee", "Destination"
    };

    /**
     * Alias map: normalizedExcelHeader → target header index.
     * Handles multiple Excel formats:
     *   - Format A: spaces ("Entity Name", "Payment Date")
     *   - Format B: underscores ("Entity_Name", "Payment_Date")
     *   - Format C: vendor-specific ("DCC_TXN_IND", "original_currency", "MSF_And_FLAT_FEE")
     */
    private static final Map<String, String> HEADER_ALIASES = new LinkedHashMap<>();
    static {
        // Identity mappings (normalized form of TARGET_HEADERS)
        for (String h : TARGET_HEADERS) {
            HEADER_ALIASES.put(normalizeHeader(h), h);
        }
        // Format B: underscore variants
        HEADER_ALIASES.put(normalizeHeader("Entity_Name"), "Entity Name");
        HEADER_ALIASES.put(normalizeHeader("Aggregator_Internal_Id"), "Aggregator Internal Id");
        HEADER_ALIASES.put(normalizeHeader("Aggregator_Name"), "Aggregator Name");
        HEADER_ALIASES.put(normalizeHeader("Aggregator_Code"), "AggregatorCode");
        HEADER_ALIASES.put(normalizeHeader("Merchant_Internal_Id"), "Merchant Internal Id");
        HEADER_ALIASES.put(normalizeHeader("Merchant_Name"), "Merchant Name");
        HEADER_ALIASES.put(normalizeHeader("Merchant_Store_Internal_Id"), "Merchant Store Internal Id");
        HEADER_ALIASES.put(normalizeHeader("CMM_Merchant_Store_Internal_Id"), "CMM Merchant Store Internal Id");
        HEADER_ALIASES.put(normalizeHeader("Merchant_Store_Legal_Name"), "Merchant Store Legal Name");
        HEADER_ALIASES.put(normalizeHeader("Store_Name"), "Store Name");
        HEADER_ALIASES.put(normalizeHeader("RRN_Number"), "RRN Number");
        HEADER_ALIASES.put(normalizeHeader("Auth_Code"), "Auth Code");
        HEADER_ALIASES.put(normalizeHeader("Payment_Date"), "Payment Date");
        HEADER_ALIASES.put(normalizeHeader("Transaction_Date"), "Transaction Date");
        HEADER_ALIASES.put(normalizeHeader("Transaction_Type"), "Transaction Type");
        HEADER_ALIASES.put(normalizeHeader("Card_Type"), "Card Type");
        HEADER_ALIASES.put(normalizeHeader("Interchange_Fee"), "Interchange Fee");
        // Format C: vendor-specific column names
        HEADER_ALIASES.put(normalizeHeader("cardScheme"), "CardScheme");
        HEADER_ALIASES.put(normalizeHeader("card_scheme"), "CardScheme");
        HEADER_ALIASES.put(normalizeHeader("DCC_TXN_IND"), "DCC");
        HEADER_ALIASES.put(normalizeHeader("dcc_txn_ind"), "DCC");
        HEADER_ALIASES.put(normalizeHeader("original_currency"), "Txn Currency");
        HEADER_ALIASES.put(normalizeHeader("original_base_currency_amount"), "Txn Currency Amount");
        HEADER_ALIASES.put(normalizeHeader("settled_currency"), "Store Base Currency");
        HEADER_ALIASES.put(normalizeHeader("store_base_currency_amount"), "Store Base Currency Amount");
        HEADER_ALIASES.put(normalizeHeader("MSF_And_FLAT_FEE"), "MSF");
        HEADER_ALIASES.put(normalizeHeader("msf_and_flat_fee"), "MSF");
        HEADER_ALIASES.put(normalizeHeader("tran_amount_settled"), "Total Amount Settled");
        HEADER_ALIASES.put(normalizeHeader("Card Destination"), "Destination");
        HEADER_ALIASES.put(normalizeHeader("card_destination"), "Destination");
    }

    /** Normalize header: lowercase, strip underscores/spaces/hyphens */
    private static String normalizeHeader(String h) {
        return h == null ? "" : h.trim().toLowerCase().replaceAll("[_ -]+", "");
    }

    /** Index of Transaction_Time column in Excel (-1 if not found) — handled specially */
    private static final String TRANSACTION_TIME_HEADER = "Transaction_Time";

    private static final int CHUNK_SIZE = 50_000; // 5x larger = fewer files, less overhead
    private static final int BUFFER_SIZE = 256 * 1024; // 256KB write buffer

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

        long startTime = System.currentTimeMillis();
        long totalRows = 0;

        try (InputStream is = new BufferedInputStream(new FileInputStream(inputFile), BUFFER_SIZE);
                ReadableWorkbook wb = new ReadableWorkbook(is)) {

            Sheet sheet = wb.getFirstSheet();
            try (Stream<Row> rows = sheet.openStream()) {
                Iterator<Row> iterator = rows.iterator();

                if (!iterator.hasNext()) {
                    return RepeatStatus.FINISHED;
                }

                // 1. Read header and build INDEX ARRAY using ALIAS-AWARE matching
                Row headerRow = iterator.next();
                int cellCount = headerRow.getCellCount();

                // Build normalized-to-column-index map from Excel headers
                Map<String, Integer> normalizedExcelMap = new HashMap<>();
                Map<String, String> rawExcelHeaders = new HashMap<>(); // for debug logging
                for (int i = 0; i < cellCount; i++) {
                    String val = headerRow.getCell(i).getText();
                    if (val != null) {
                        String norm = normalizeHeader(val);
                        normalizedExcelMap.put(norm, i);
                        rawExcelHeaders.put(norm, val.trim());
                    }
                }

                // Pre-compute: for each target column, find source index via alias map
                int[] sourceIndexes = new int[TARGET_HEADERS.length];
                // Build target header name -> target index lookup
                Map<String, Integer> targetNameToIdx = new HashMap<>();
                for (int i = 0; i < TARGET_HEADERS.length; i++) {
                    targetNameToIdx.put(TARGET_HEADERS[i], i);
                    sourceIndexes[i] = -1; // default: not found
                }

                // Try alias matching: for each Excel header, find its target
                for (Map.Entry<String, Integer> excelEntry : normalizedExcelMap.entrySet()) {
                    String normalizedExcel = excelEntry.getKey();
                    String targetName = HEADER_ALIASES.get(normalizedExcel);
                    if (targetName != null) {
                        Integer targetIdx = targetNameToIdx.get(targetName);
                        if (targetIdx != null) {
                            sourceIndexes[targetIdx] = excelEntry.getValue();
                        }
                    }
                }

                // Detect Transaction_Time column (separate time column to merge with Transaction_Date)
                int timeColumnIdx = -1;
                for (Map.Entry<String, Integer> e : normalizedExcelMap.entrySet()) {
                    if (e.getKey().equals(normalizeHeader(TRANSACTION_TIME_HEADER))
                            || e.getKey().equals("transactiontime")) {
                        timeColumnIdx = e.getValue();
                        break;
                    }
                }
                final int txnTimeColIdx = timeColumnIdx;
                // Transaction Date target index for merging time
                final int txnDateTargetIdx = targetNameToIdx.getOrDefault("Transaction Date", -1);
                final int txnDateSrcIdx = txnDateTargetIdx >= 0 ? sourceIndexes[txnDateTargetIdx] : -1;

                // Log mapping results
                int matched = 0;
                for (int i = 0; i < TARGET_HEADERS.length; i++) {
                    if (sourceIndexes[i] >= 0) matched++;
                    else System.out.printf("  ⚠️  Column '%s' not found in Excel%n", TARGET_HEADERS[i]);
                }
                System.out.printf("Header mapping: %d/%d columns matched (Transaction_Time col: %d)%n",
                        matched, TARGET_HEADERS.length, txnTimeColIdx);

                // Build CSV header string once
                StringBuilder headerSb = new StringBuilder(512);
                for (int i = 0; i < TARGET_HEADERS.length; i++) {
                    if (i > 0) headerSb.append(',');
                    headerSb.append('"').append(TARGET_HEADERS[i]).append('"');
                }
                String headerLine = headerSb.toString();

                // 2. Stream rows into chunked CSV files
                int fileIndex = 1;
                int rowCount = 0;
                BufferedWriter writer = createWriter(outputDir, fileIndex, headerLine);
                StringBuilder sb = new StringBuilder(2048); // reuse per row

                while (iterator.hasNext()) {
                    Row row = iterator.next();
                    int maxCell = row.getCellCount();

                    sb.setLength(0); // reset without realloc
                    for (int i = 0; i < TARGET_HEADERS.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append('"');

                        int srcIdx = sourceIndexes[i];
                        if (srcIdx >= 0 && srcIdx < maxCell) {
                            String val = row.getCell(srcIdx).getText();
                            // Fallback: getText() returns empty for date/numeric cells
                            // stored as Excel serial numbers — use raw value instead
                            if ((val == null || val.isEmpty())) {
                                val = row.getCell(srcIdx).getRawValue();
                            }
                            if (val != null && !val.isEmpty()) {
                                val = val.trim(); // Always trim whitespace (fixes "Credit " etc.)

                                // Special: merge Transaction_Time into Transaction_Date
                                if (i == txnDateTargetIdx && txnTimeColIdx >= 0 && txnTimeColIdx < maxCell) {
                                    String timeVal = row.getCell(txnTimeColIdx).getText();
                                    if (timeVal != null && !timeVal.trim().isEmpty()) {
                                        // If date doesn't already contain time, append it
                                        if (!val.contains(":") && !val.contains("T")) {
                                            val = val + " " + timeVal.trim();
                                        }
                                    }
                                }

                                // Inline CSV escape — avoid String.replace() allocation
                                for (int c = 0; c < val.length(); c++) {
                                    char ch = val.charAt(c);
                                    if (ch == '"') sb.append('"'); // escape double-quote
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

                // Store total row count for progress tracking
                chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext()
                        .putLong("totalReqRows", totalRows);

                long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("Split complete: %,d rows → %d files in %.1fs (%,.0f rows/sec)%n",
                        totalRows, fileIndex, elapsed / 1000.0, totalRows * 1000.0 / Math.max(elapsed, 1));
            }
        }

        return RepeatStatus.FINISHED;
    }

    private BufferedWriter createWriter(Path dir, int index, String header) throws Exception {
        File f = dir.resolve("part_" + String.format("%03d", index) + ".csv").toFile();
        BufferedWriter bw = new BufferedWriter(new FileWriter(f), BUFFER_SIZE);
        bw.write(header);
        bw.newLine();
        return bw;
    }
}
