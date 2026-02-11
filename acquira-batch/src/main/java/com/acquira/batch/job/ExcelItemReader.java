package com.acquira.batch.job;

import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Iterator;

public class ExcelItemReader<T> extends AbstractItemCountingItemStreamItemReader<T> {

    private Resource resource;
    private RowMapper<T> rowMapper;
    private ReadableWorkbook workbook;
    private Iterator<Row> rowIterator;
    private boolean linesToSkipConfigured = false;
    private int linesToSkip = 1; // Default skip header

    private Map<String, Integer> headerMap = new HashMap<>();

    public ExcelItemReader() {
        setName("ExcelItemReader");
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }

    public void setRowMapper(RowMapper<T> rowMapper) {
        this.rowMapper = rowMapper;
    }

    public void setLinesToSkip(int linesToSkip) {
        this.linesToSkip = linesToSkip;
    }

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ExcelItemReader.class);

    @Override
    protected void doOpen() throws Exception {
        Assert.notNull(resource, "Input resource must be set");
        if (!resource.exists()) {
            throw new IllegalStateException("Input resource does not exist: " + resource.getDescription());
        }

        InputStream inputStream = resource.getInputStream();
        this.workbook = new ReadableWorkbook(inputStream);

        Sheet sheet = this.workbook.getFirstSheet();
        Stream<Row> rowStream = sheet.openStream();
        this.rowIterator = rowStream.iterator();

        logger.info("ExcelItemReader opened resource: {}", resource.getDescription());

        if (linesToSkip > 0 && rowIterator.hasNext()) {
            Row headerRow = rowIterator.next();
            for (org.dhatim.fastexcel.reader.Cell cell : headerRow) {
                String h = cell.getText();
                if (h != null) {
                    // Normalize: lowercase, no spaces, no underscores
                    String key = h.trim().toLowerCase().replace(" ", "").replace("_", "");
                    headerMap.put(key, cell.getColumnIndex());
                }
            }
            logger.info("Mapped {} headers from Excel file.", headerMap.size());

            for (int i = 1; i < linesToSkip; i++) {
                if (rowIterator.hasNext()) {
                    rowIterator.next();
                }
            }
            logger.info("Header check complete. Iterator hasNext: {}", rowIterator.hasNext());
        }
    }

    @Override
    protected T doRead() throws Exception {
        if (rowIterator != null && rowIterator.hasNext()) {
            Row row = rowIterator.next();
            int rowNum = row.getRowNum();
            // Log first few rows to ensure data is being read
            if (rowNum < 5) {
                logger.info("Reading Row #{}: {}", rowNum, row.toString());
            }
            return rowMapper.mapRow(row, rowNum);
        }
        logger.warn("doRead: Iterator exhausted or null.");
        return null;
    }

    @Override
    protected void doClose() throws Exception {
        if (this.workbook != null) {
            this.workbook.close();
        }
        if (getCurrentItemCount() == 0) {
            // Log warning but do NOT throw exception as it might be a false positive due to
            // scoping
            logger.warn(
                    "doClose: getCurrentItemCount() is 0. This might be due to StepScope or empty file. Header Map: {}",
                    headerMap.keySet());
        }
    }

    public String getCellValue(Row row, String headerName) {
        String key = headerName.trim().toLowerCase().replace(" ", "").replace("_", "");
        if (headerMap.containsKey(key)) {
            int idx = headerMap.get(key);
            if (idx < row.getCellCount()) {
                org.dhatim.fastexcel.reader.Cell cell = row.getCell(idx);
                return cell != null ? cell.getText() : null;
            }
        } else {
            // Log warning only once per header to avoid flooding?
            // For now, let's debug log.
            // logger.warn("Header '{}' (key: '{}') not found in file. Available: {}",
            // headerName, key, headerMap.keySet());
        }
        return null;
    }

    public interface RowMapper<T> {
        T mapRow(Row row, int rowNum) throws Exception;
    }
}
