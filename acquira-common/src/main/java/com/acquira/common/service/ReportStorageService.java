package com.acquira.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * Abstracted report storage service.
 *
 * Supports two modes controlled by per-tenant S3 settings stored in tenant_setting:
 *   - "local"  : write PDFs to local filesystem (default)
 *   - "s3"     : write locally AND upload to S3 after merchant email is sent
 *
 * S3 operations are delegated to ReportS3UploadService (in acquira-core)
 * which has access to S3EncryptionService and TenantSettingRepository.
 * This class stays in acquira-common and remains storage-agnostic.
 */
@Service
public class ReportStorageService {

    private static final Logger log = LoggerFactory.getLogger(ReportStorageService.class);

    @Value("${pdf.reports.dir:reports}")
    private String reportsBaseDir;

    /** Get the base reports root path (local mode) */
    public Path getReportsRoot() {
        return Paths.get(reportsBaseDir).toAbsolutePath().normalize();
    }

    /** Resolve a tenant+month folder, with fallback to legacy flat structure */
    public Path resolveFolder(String bankShortCode, String yearMonth) {
        Path root = getReportsRoot();
        if (bankShortCode != null && !bankShortCode.isBlank()) {
            Path tenantPath = root.resolve(bankShortCode).resolve(yearMonth);
            if (Files.exists(tenantPath)) return tenantPath;
        }
        Path flatPath = root.resolve(yearMonth);
        if (Files.exists(flatPath)) return flatPath;
        if (bankShortCode != null && !bankShortCode.isBlank()) {
            return root.resolve(bankShortCode).resolve(yearMonth);
        }
        return flatPath;
    }

    /** Resolve a specific report file, trying tenant folder then legacy */
    public Path resolveFile(String filename, String bankShortCode, String yearMonth) {
        Path folder   = resolveFolder(bankShortCode, yearMonth);
        Path filePath = folder.resolve(filename);
        if (Files.exists(filePath)) return filePath;
        Path legacyPath = getReportsRoot().resolve(yearMonth).resolve(filename);
        if (Files.exists(legacyPath)) return legacyPath;
        return filePath;
    }

    /** Write a report file to local disk */
    public void writeReport(byte[] content, String bankShortCode, String yearMonth, String filename) throws IOException {
        Path folder = getReportsRoot();
        if (bankShortCode != null && !bankShortCode.isBlank()) {
            folder = folder.resolve(bankShortCode);
        }
        folder = folder.resolve(yearMonth);
        Files.createDirectories(folder);
        Files.write(folder.resolve(filename), content);
        log.debug("Wrote report: {}", folder.resolve(filename));
    }

    /** List all PDF files in a report folder */
    public List<Path> listReports(String bankShortCode, String yearMonth) throws IOException {
        Path folder = resolveFolder(bankShortCode, yearMonth);
        if (!Files.exists(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.toString().endsWith(".pdf")).sorted().toList();
        }
    }

    /** Storage info description for UI/API responses */
    public String getStorageInfo() {
        return "Local (" + getReportsRoot() + ") + optional S3 per-tenant setting";
    }
}
