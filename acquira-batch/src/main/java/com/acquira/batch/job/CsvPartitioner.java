package com.acquira.batch.job;

import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public class CsvPartitioner implements Partitioner {

    private String partitionDirectory;

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> map = new HashMap<>();

        if (partitionDirectory == null) {
            throw new IllegalArgumentException("Partition directory not set in context!");
        }

        Path dir = Paths.get(partitionDirectory);
        if (!Files.exists(dir)) {
            throw new IllegalArgumentException("Partition directory does not exist: " + partitionDirectory);
        }

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".csv"))
                    .forEach(path -> {
                        ExecutionContext context = new ExecutionContext();
                        context.putString("fileName", path.toAbsolutePath().toString());
                        map.put("partition_" + path.getFileName().toString(), context);
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list partition files", e);
        }

        return map;
    }

    public void setPartitionDirectory(String partitionDirectory) {
        this.partitionDirectory = partitionDirectory;
    }
}
