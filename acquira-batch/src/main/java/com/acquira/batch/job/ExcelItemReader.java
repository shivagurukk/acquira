package com.acquira.batch.job;

import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.io.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Unified reader that handles both Excel (.xlsx) and CSV/TSV files.
 * Auto-detects format based on file extension.
 *
 * For Excel: uses fastexcel SAX-based streaming (low memory).
 * For CSV:   uses BufferedReader streaming (zero memory overhead).
 *
 * Provides getCellValue(row, headerName) for Excel rows
 * and getCsvCellValue(headerName) for CSV rows.
 */
public class ExcelItemReader<T> extends AbstractItemCountingItemStreamItemReader<T> {

    private Resource resource;
    private RowMapper<T> rowMapper;
    private CsvRowMapper<T> csvRowMapper;
    private int linesToSkip = 1;

    // Excel mode
    private ReadableWorkbook workbook;
    private Iterator<Row> rowIterator;

    // CSV mode
    private BufferedReader csvReader;
    private boolean csvMode = false;
    private char csvDelimiter = ',';
    private String[] currentCsvFields;
    private int csvRowNum = 0;

    // Shared
    private Map<String, Integer> headerMap = new HashMap<>();

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ExcelItemReader.class);

    public ExcelItemReader() {
        setName("ExcelItemReader");
    }

    public void setResource(Resource resource) { this.resource = resource; }
    public void setRowMapper(RowMapper<T> rowMapper) { this.rowMapper = rowMapper; }
    public void setCsvRowMapper(CsvRowMapper<T> csvRowMapper) { this.csvRowMapper = csvRowMapper; }
    public void setLinesToSkip(int linesToSkip) { this.linesToSkip = linesToSkip; }

    @Override
    protected void doOpen() throws Exception {
        Assert.notNull(resource, "Input resource must be set");
        if (!resource.exists()) {
            throw new IllegalStateException("Input resource does not exist: " + resource.getDescription());
        }

        String filename = resource.getFilename() != null ? resource.getFilename().toLowerCase() : "";
        csvMode = filename.endsWith(".csv") || filename.endsWith(".tsv") || filename.endsWith(".txt");

        if (csvMode) {
            openCsv();
        } else {
            openExcel();
        }
    }

    // ==================================================================================
    // CSV MODE
    // ==================================================================================
    private void openCsv() throws Exception {
        InputStream is = resource.getInputStream();
        csvReader = new BufferedReader(new InputStreamReader(is), 256 * 1024);

        // Read header line
        String headerLine = csvReader.readLine();
        if (headerLine == null) {
            logger.warn("CSV file is empty: {}", resource.getDescription());
            return;
        }

        // Detect delimiter
        csvDelimiter = headerLine.contains("\t") ? '\t' : ',';

        // Parse headers and build headerMap
        String[] headers = parseCsvLine(headerLine, csvDelimiter);
        for (int i = 0; i < headers.length; i++) {
            String key = headers[i].trim().toLowerCase().replace(" ", "").replace("_", "");
            headerMap.put(key, i);
        }

        // Skip additional lines if needed (linesToSkip includes header which we already read)
        for (int i = 1; i < linesToSkip; i++) {
            csvReader.readLine();
        }

        logger.info("CSV reader opened: {} — {} headers, delimiter='{}'",
                resource.getDescription(), headerMap.size(), csvDelimiter == '\t' ? "TAB" : ",");
    }

    private T readCsv() throws Exception {
        String line = csvReader.readLine();
        while (line != null && line.trim().isEmpty()) {
            line = csvReader.readLine(); // skip blank lines
        }
        if (line == null) return null;

        currentCsvFields = parseCsvLine(line, csvDelimiter);
        csvRowNum++;

        if (csvRowMapper != null) {
            return csvRowMapper.mapRow(this, csvRowNum);
        }

        // Fallback: if only rowMapper is set (not csvRowMapper), wrap CSV fields
        // into a fake Row — but this requires the RowMapper to use getCellValue
        // which won't work directly. Instead, the caller should set csvRowMapper.
        // For backward compat with merchant job, return null and log warning.
        logger.warn("CSV mode requires setCsvRowMapper(). Row {} skipped.", csvRowNum);
        return null;
    }

    /**
     * Get a cell value from the current CSV row by header name.
     * Works like getCellValue() but for CSV rows.
     */
    public String getCsvCellValue(String headerName) {
        if (currentCsvFields == null) return null;
        String key = headerName.trim().toLowerCase().replace(" ", "").replace("_", "");
        Integer idx = headerMap.get(key);
        if (idx != null && idx < currentCsvFields.length) {
            String val = currentCsvFields[idx];
            return (val != null && !val.isEmpty()) ? val : null;
        }
        return null;
    }

    // ==================================================================================
    // EXCEL MODE (existing logic)
    // ==================================================================================
    private void openExcel() throws Exception {
        InputStream inputStream = resource.getInputStream();
        this.workbook = new ReadableWorkbook(inputStream);

        Sheet sheet = this.workbook.getFirstSheet();
        Stream<Row> rowStream = sheet.openStream();
        this.rowIterator = rowStream.iterator();

        logger.info("ExcelItemReader opened resource: {}", resource.getDescription());
        // PERF: header-mapping detail dropped from INFO to DEBUG. The merchant master file
        // has ~70 columns, which produced 70+ log lines per upload. With file logging
        // enabled that's measurable latency per upload and noise in production logs.

        if (linesToSkip > 0 && rowIterator.hasNext()) {
            Row headerRow = rowIterator.next();
            for (org.dhatim.fastexcel.reader.Cell cell : headerRow) {
                String h = cell.getText();
                if (h != null) {
                    String key = h.trim().toLowerCase().replace(" ", "").replace("_", "");
                    headerMap.put(key, cell.getColumnIndex());
                    if (logger.isDebugEnabled()) {
                        logger.debug("  Col {}: '{}' -> normalized: '{}'", cell.getColumnIndex(), h, key);
                    }
                }
            }
            logger.info("Mapped {} headers from Excel file.", headerMap.size());

            for (int i = 1; i < linesToSkip; i++) {
                if (rowIterator.hasNext()) rowIterator.next();
            }
        }
    }

    private T readExcel() throws Exception {
        if (rowIterator != null && rowIterator.hasNext()) {
            Row row = rowIterator.next();
            // PERF: per-row INFO log dropped to DEBUG. With ~100k transaction rows this
            // produced 5 "Reading Row #N" log lines per upload at INFO — fine, but the
            // condition was checked inside the hot loop. Move to DEBUG entirely.
            if (logger.isDebugEnabled() && row.getRowNum() < 5) {
                logger.debug("Reading Row #{}", row.getRowNum());
            }
            return rowMapper.mapRow(row, row.getRowNum());
        }
        return null;
    }

    // ==================================================================================
    // COMMON
    // ==================================================================================
    @Override
    protected T doRead() throws Exception {
        return csvMode ? readCsv() : readExcel();
    }

    @Override
    protected void doClose() throws Exception {
        if (this.workbook != null) this.workbook.close();
        if (this.csvReader != null) this.csvReader.close();
    }

    public boolean isCsvMode() {
        return csvMode;
    }

    /**
     * Get cell value from an Excel row by header name.
     * Only works in Excel mode.
     */
    public String getCellValue(Row row, String headerName) {
        String key = headerName.trim().toLowerCase().replace(" ", "").replace("_", "");
        if (headerMap.containsKey(key)) {
            int idx = headerMap.get(key);
            if (idx < row.getCellCount()) {
                org.dhatim.fastexcel.reader.Cell cell = row.getCell(idx);
                return cell != null ? cell.getText() : null;
            }
        }
        return null;
    }

    /**
     * Parse a CSV line respecting quoted fields.
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

    // Interfaces
    public interface RowMapper<T> {
        T mapRow(Row row, int rowNum) throws Exception;
    }

    public interface CsvRowMapper<T> {
        T mapRow(ExcelItemReader<?> reader, int rowNum) throws Exception;
    }
}
