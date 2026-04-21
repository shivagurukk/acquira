package com.acquira.common.service;

import java.nio.file.Path;

/**
 * Interface for uploading generated PDF reports to object storage (e.g. AWS S3).
 *
 * Lives in acquira-common so that acquira-pdf can depend on it without
 * creating a circular dependency with acquira-core (which holds the
 * AWS-SDK-backed implementation).
 *
 * acquira-pdf  →  acquira-common  (compile dependency — OK)
 * acquira-core →  acquira-common  (compile dependency — OK)
 * acquira-core implements S3Uploader, Spring wires it into PdfController at runtime.
 *
 * PdfController declares:
 *   @Autowired(required = false)
 *   private S3Uploader s3Uploader;
 *
 * When running inside acquira-core the real implementation is injected.
 * When running standalone (acquira-pdf only) the field stays null and S3 is skipped.
 */
public interface S3Uploader {

    /**
     * Upload a PDF to object storage if S3 is enabled for the given tenant.
     *
     * @param tenantId  tenant that owns the report (null = skip silently)
     * @param pdfPath   local path to the generated PDF file
     * @param bankCode  bank short code — used as the first S3 path segment
     * @param yearMonth report month in YYYY-MM format (e.g. "2025-03")
     * @return {@code true} if the upload succeeded or S3 is disabled for this tenant;
     *         {@code false} if S3 is enabled but the upload failed
     */
    boolean uploadIfEnabled(Long tenantId, Path pdfPath, String bankCode, String yearMonth);
}
