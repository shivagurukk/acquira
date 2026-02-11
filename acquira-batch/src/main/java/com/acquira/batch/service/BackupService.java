package com.acquira.batch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class BackupService {

    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    private final String BACKUP_DIR = "./backups";

    public BackupService() {
        // Ensure backup directory exists
        File directory = new File(BACKUP_DIR);
        if (!directory.exists()) {
            boolean created = directory.mkdirs();
            if (created) {
                logger.info("Backup directory created at: {}", BACKUP_DIR);
            }
        }
    }

    public String createBackup() throws IOException, InterruptedException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "backup_acquira_" + timestamp + ".sql";
        Path backupPath = Paths.get(BACKUP_DIR, fileName);

        // Parse DB config from URL (assuming jdbc:postgresql://host:port/dbname)
        // jdbc:postgresql://127.0.0.1:5433/postgres?reWriteBatchedInserts=true
        String cleanUrl = dbUrl.replace("jdbc:", "");
        String[] parts = cleanUrl.split("\\?");
        String hostPortDb = parts[0]; // postgresql://127.0.0.1:5433/postgres

        // Extract host, port, dbname
        // Expected format: postgresql://host:port/dbname
        // simplified parsing logic
        String dbName = "postgres"; // default fallback or parse
        String host = "localhost";
        String port = "5432";

        if (hostPortDb.startsWith("postgresql://")) {
            String temp = hostPortDb.substring("postgresql://".length());
            String[] split1 = temp.split("/");
            if (split1.length > 0) {
                String[] hostPort = split1[0].split(":");
                host = hostPort[0];
                if (hostPort.length > 1) {
                    port = hostPort[1];
                }
            }
            if (split1.length > 1) {
                dbName = split1[1];
            }
        }

        logger.info("Starting backup for DB: {} on {}:{}", dbName, host, port);

        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump",
                "-h", host,
                "-p", port,
                "-U", dbUser,
                "-F", "c", // Custom format (better for restore)
                "-b", // Include LOBs
                "-v", // Verbose
                "-f", backupPath.toAbsolutePath().toString(),
                dbName);

        pb.environment().put("PGPASSWORD", dbPassword);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Capture output for logging
        new Thread(() -> {
            try (java.util.Scanner s = new java.util.Scanner(process.getInputStream())) {
                while (s.hasNextLine())
                    logger.debug(s.nextLine());
            }
        }).start();

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);

        if (finished && process.exitValue() == 0) {
            logger.info("Backup created successfully: {}", fileName);
            return fileName;
        } else {
            throw new IOException("Backup failed. Exit code: " + (finished ? process.exitValue() : "TIMEOUT"));
        }
    }

    public List<BackupFile> listBackups() {
        File folder = new File(BACKUP_DIR);
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".sql") || name.endsWith(".dump"));

        if (files == null)
            return Collections.emptyList();

        return Arrays.stream(files)
                .map(file -> new BackupFile(
                        file.getName(),
                        file.length(),
                        file.lastModified()))
                .sorted(Comparator.comparingLong(BackupFile::lastModified).reversed())
                .collect(Collectors.toList());
    }

    public void restoreBackup(String fileName) throws IOException, InterruptedException {
        Path backupPath = Paths.get(BACKUP_DIR, fileName);
        if (!Files.exists(backupPath)) {
            throw new IOException("Backup file not found: " + fileName);
        }

        // Logic similar to backup but using pg_restore
        String cleanUrl = dbUrl.replace("jdbc:", "");
        String[] parts = cleanUrl.split("\\?");
        String hostPortDb = parts[0];

        String dbName = "postgres";
        String host = "localhost";
        String port = "5432";

        if (hostPortDb.startsWith("postgresql://")) {
            String temp = hostPortDb.substring("postgresql://".length());
            String[] split1 = temp.split("/");
            if (split1.length > 0) {
                String[] hostPort = split1[0].split(":");
                host = hostPort[0];
                if (hostPort.length > 1) {
                    port = hostPort[1];
                }
            }
            if (split1.length > 1) {
                dbName = split1[1];
            }
        }

        logger.warn("Restoring backup {} to DB: {}", fileName, dbName);

        // pg_restore -h localhost -p 5432 -U postgres -d dbname -v -c "file"
        // -c : Clean (drop) database objects before creating them
        ProcessBuilder pb = new ProcessBuilder(
                "pg_restore",
                "-h", host,
                "-p", port,
                "-U", dbUser,
                "-d", dbName,
                "-v",
                "-c",
                "--if-exists",
                backupPath.toAbsolutePath().toString());

        pb.environment().put("PGPASSWORD", dbPassword);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        new Thread(() -> {
            try (java.util.Scanner s = new java.util.Scanner(process.getInputStream())) {
                while (s.hasNextLine())
                    logger.debug(s.nextLine());
            }
        }).start();

        boolean finished = process.waitFor(120, TimeUnit.SECONDS); // Give more time for restore

        if (finished && process.exitValue() == 0) {
            logger.info("Restore completed successfully.");
        } else {
            // 1 is often non-fatal warnings with pg_restore, but 0 is strict success.
            // We'll treat non-zero carefully.
            if (finished && process.exitValue() <= 1) {
                logger.info("Restore completed with possible warnings (Exit code 1).");
            } else {
                throw new IOException("Restore failed. Exit code: " + (finished ? process.exitValue() : "TIMEOUT"));
            }
        }
    }

    public void deleteBackup(String fileName) throws IOException {
        Path backupPath = Paths.get(BACKUP_DIR, fileName);
        Files.deleteIfExists(backupPath);
    }

    public File getBackupFile(String fileName) {
        return Paths.get(BACKUP_DIR, fileName).toFile();
    }

    // DTO for UI
    public record BackupFile(String name, long size, long lastModified) {
    }
}
