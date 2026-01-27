package com.acquira.batch;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.batch.item.ExecutionContext;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ExcelItemReaderReproducerTest {

    @Test
    public void testConcurrentModificationException() throws Exception {
        ExcelItemReader<Object> reader = new ExcelItemReader<>();
        reader.setRowMapper((row, rowNum) -> {
            for (org.dhatim.fastexcel.reader.Cell cell : row) {
                cell.getText();
            }
            return rowNum;
        });

        String filePath = "C:/Users/sivag/Desktop/cms/Acquira/data/uploads/1766848889970_trnx_sample_1k_2025_all_combinations.xlsx";
        reader.setResource(new FileSystemResource(new File(filePath)));
        reader.setLinesToSkip(1);

        reader.open(new ExecutionContext());

        Object items;
        int count = 0;
        try {
            while ((items = reader.read()) != null) {
                count++;
                if (count % 100 == 0) {
                    System.out.println("Read " + count + " items");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            reader.close();
        }
        System.out.println("Finished reading " + count + " items");
    }
}
