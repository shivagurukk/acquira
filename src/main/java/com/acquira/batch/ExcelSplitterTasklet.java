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

                // 1. Read header and build INDEX ARRAY (not map lookup per cell)
                Row headerRow = iterator.next();
                Map<String, Integer> headerMap = new HashMap<>();
                int cellCount = headerRow.getCellCount();
                for (int i = 0; i < cellCount; i++) {
                    String val = headerRow.getCell(i).getText();
                    if (val != null) headerMap.put(val.trim(), i);
                }

                // Pre-compute: for each target column, what source index?
                int[] sourceIndexes = new int[TARGET_HEADERS.length];
                for (int i = 0; i < TARGET_HEADERS.length; i++) {
                    Integer idx = headerMap.get(TARGET_HEADERS[i]);
                    sourceIndexes[i] = (idx != null) ? idx : -1;
                }

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
                            if (val != null && !val.isEmpty()) {
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
