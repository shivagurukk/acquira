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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Component
@Scope(value = "step", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class ExcelSplitterTasklet implements Tasklet {

    @Value("#{jobParameters['fullPath']}")
    private String fullPath;

    @Value("#{jobParameters['jobId']}") // We can use job execution ID ideally, but parameter logic works
    private String distinctId;

    // Define the expected order EXACTLY as per TransactionJobConfig
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

    private static final int CHUNK_SIZE = 10000;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        Assert.notNull(fullPath, "Input file path must not be null");
        File inputFile = new File(fullPath);
        if (!inputFile.exists()) {
            throw new IllegalStateException("Input file does not exist: " + fullPath);
        }

        // Create a temp directory for this job execution
        String jobId = chunkContext.getStepContext().getStepExecution().getJobExecution().getId().toString();
        Path outputDir = Paths.get("temp", "job_" + jobId);
        Files.createDirectories(outputDir);

        chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext()
                .put("partitionDirectory", outputDir.toAbsolutePath().toString());

        try (InputStream is = new FileInputStream(inputFile);
                ReadableWorkbook wb = new ReadableWorkbook(is)) {

            Sheet sheet = wb.getFirstSheet();
            try (Stream<Row> rows = sheet.openStream()) {
                var iterator = rows.iterator();

                if (!iterator.hasNext()) {
                    return RepeatStatus.FINISHED;
                }

                // 1. Read Header and Build Mapping
                Row headerRow = iterator.next();
                java.util.Map<String, Integer> headerMap = new java.util.HashMap<>();
                int cellCount = headerRow.getCellCount();
                for (int i = 0; i < cellCount; i++) {
                    String val = headerRow.getCell(i).getText();
                    if (val != null) {
                        headerMap.put(val.trim(), i);
                    }
                }

                // Create Standardized CSV Header String
                String headerLine = String.join(",", java.util.Arrays.stream(TARGET_HEADERS)
                        .map(h -> "\"" + h + "\"")
                        .toArray(String[]::new));

                int fileIndex = 1;
                int rowCount = 0;
                BufferedWriter writer = createWriter(outputDir, fileIndex, headerLine);

                while (iterator.hasNext()) {
                    Row row = iterator.next();
                    // 2. Map Row to Target Order
                    String line = mapRowToCsv(row, headerMap);

                    if (writer == null) {
                        writer = createWriter(outputDir, fileIndex, headerLine);
                    }

                    writer.write(line);
                    writer.newLine();
                    rowCount++;

                    if (rowCount >= CHUNK_SIZE) {
                        try {
                            writer.close();
                        } catch (Exception ignored) {
                        }
                        writer = null;
                        fileIndex++;
                        rowCount = 0;
                    }
                }

                if (writer != null) {
                    try {
                        writer.close();
                    } catch (Exception ignored) {
                    }
                }

                System.out.println("Split complete. Created " + fileIndex + " partitions in " + outputDir);
            }
        }

        return RepeatStatus.FINISHED;
    }

    private BufferedWriter createWriter(Path dir, int index, String header) throws Exception {
        File f = dir.resolve("part_" + String.format("%03d", index) + ".csv").toFile();
        BufferedWriter bw = new BufferedWriter(new FileWriter(f));
        if (header != null) {
            bw.write(header);
            bw.newLine();
        }
        return bw;
    }

    private String mapRowToCsv(Row row, java.util.Map<String, Integer> headerMap) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TARGET_HEADERS.length; i++) {
            if (i > 0)
                sb.append(",");

            String targetCol = TARGET_HEADERS[i];
            Integer sourceIndex = headerMap.get(targetCol);

            String val = "";
            if (sourceIndex != null && sourceIndex < row.getCellCount()) {
                val = row.getCell(sourceIndex).getText();
            }

            if (val == null)
                val = "";
            // CSV Escaping
            val = val.replace("\"", "\"\"");
            sb.append("\"").append(val).append("\"");
        }
        return sb.toString();
    }
}
