package com.acquira.batch.service;
import com.acquira.common.service.AuditService;

import com.acquira.common.config.TenantContext;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;

@Service
public class FileUploadService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadService.class);

    private final JobLauncher jobLauncher;
    private final Job merchantMasterJob;
    private final Job transactionLoadJob;

    private final String UPLOAD_DIR = "data/uploads/";

    private final com.acquira.common.service.AuditService auditService;
    private final com.acquira.common.repository.TenantRepository tenantRepository;
    private final com.acquira.batch.service.ManualIngestionService manualIngestionService;

    /**
     * Allowed directories for server-side file processing.
     * P2-6 fix: validation now lives in the service layer so any future caller
     * (scheduler, MCP integration, another controller) cannot bypass the check.
     * Defaults to the same paths the controller previously enforced.
     */
    @org.springframework.beans.factory.annotation.Value("${app.upload.allowed-paths:/opt/acquira/data,data/uploads,data/imports}")
    private String allowedPathsCsv;

    public FileUploadService(JobLauncher jobLauncher,
            @Qualifier("merchantMasterJob") Job merchantMasterJob,
            @Qualifier("transactionLoadJob") Job transactionLoadJob,
            com.acquira.common.service.AuditService auditService,
            com.acquira.common.repository.TenantRepository tenantRepository,
            com.acquira.batch.service.ManualIngestionService manualIngestionService) {
        this.jobLauncher = jobLauncher;
        this.merchantMasterJob = merchantMasterJob;
        this.transactionLoadJob = transactionLoadJob;
        this.auditService = auditService;
        this.tenantRepository = tenantRepository;
        this.manualIngestionService = manualIngestionService;

        // Ensure upload directory exists
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize upload folder", e);
        }
    }

    // A helper for robust reading
    private String getCellValue(Cell cell) {
        if (cell == null)
            return "";
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell);
    }

    /** Holder for the result of a single-pass file scan. */
    private static class FileScanResult {
        String detectedType;  // MERCHANT, TRANSACTION, LEGACY_EXCEL, UNKNOWN
        String entityId;      // value of row 2 col 1 (or null)
    }

    /**
     * PERF FIX: read header row + entity ID in ONE workbook open.
     * Replaces detectFileType() + resolveTargetTenant() reading the file twice.
     */
    private FileScanResult scanFileOnce(String filePath) {
        FileScanResult result = new FileScanResult();
        result.detectedType = "UNKNOWN";
        String lowerPath = filePath.toLowerCase();

        // CSV/TSV/TXT: read first two lines
        if (lowerPath.endsWith(".csv") || lowerPath.endsWith(".tsv") || lowerPath.endsWith(".txt")) {
            try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
                String headerLine = br.readLine();
                if (headerLine != null) {
                    String h = headerLine.toLowerCase();
                    if (h.contains("transaction id") || h.contains("txn id") || h.contains("ref number")
                            || h.contains("transaction date") || h.contains("payment date")
                            || h.contains("arn") || h.contains("rrn") || h.contains("txn currency")) {
                        result.detectedType = "TRANSACTION";
                    } else if (h.contains("merchant name") || h.contains("merchant id") || h.contains("mid")) {
                        result.detectedType = "MERCHANT";
                    }
                }
                String dataLine = br.readLine();
                if (dataLine != null && !dataLine.isEmpty()) {
                    char delim = dataLine.contains("\t") ? '\t' : ',';
                    int end = dataLine.indexOf(delim);
                    String entity = end > 0 ? dataLine.substring(0, end) : dataLine;
                    result.entityId = entity.replace("\"", "").trim();
                }
            } catch (Exception e) {
                log.warn("Could not scan CSV file: {}", e.getMessage());
            }
            return result;
        }

        // Excel: open workbook ONCE
        try (InputStream is = new FileInputStream(filePath);
                Workbook workbook = WorkbookFactory.create(is)) {

            if (workbook instanceof org.apache.poi.hssf.usermodel.HSSFWorkbook) {
                result.detectedType = "LEGACY_EXCEL";
                return result;
            }

            Sheet sheet = workbook.getSheetAt(0);

            // Row 0: detect type from headers
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                boolean hasTransactionId = false;
                boolean hasMerchantMarker = false;
                for (Cell cell : headerRow) {
                    String header = getCellValue(cell);
                    if (header != null) {
                        String h = header.trim().toLowerCase();
                        if (h.contains("transaction id") || h.contains("txn id") || h.equals("ref number")
                                || h.contains("transaction date") || h.contains("payment date")
                                || h.contains("arn") || h.contains("rrn") || h.contains("txn currency")) {
                            hasTransactionId = true;
                        }
                        if (h.contains("merchant name") || h.contains("merchant id") || h.equals("mid")) {
                            hasMerchantMarker = true;
                        }
                    }
                }
                if (hasTransactionId) result.detectedType = "TRANSACTION";
                else if (hasMerchantMarker) result.detectedType = "MERCHANT";
            }

            // Row 1: entity ID at col 0 (same workbook — no re-open)
            if (sheet.getLastRowNum() >= 1) {
                Row row = sheet.getRow(1);
                if (row != null) {
                    Cell cell = row.getCell(0);
                    result.entityId = getCellValue(cell);
                }
            }
        } catch (org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
                | org.apache.poi.poifs.filesystem.OfficeXmlFileException e) {
            log.warn("File is not Excel, retrying as CSV: {}", e.getMessage());
            // Fall through — try CSV reader
            try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
                String headerLine = br.readLine();
                if (headerLine != null) {
                    String h = headerLine.toLowerCase();
                    if (h.contains("transaction") || h.contains("arn") || h.contains("rrn")) result.detectedType = "TRANSACTION";
                    else if (h.contains("merchant") || h.contains("mid")) result.detectedType = "MERCHANT";
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.warn("Error scanning file: {}", e.getMessage());
        }
        return result;
    }

    /**
     * PERF FIX: tenant resolution using already-extracted entity ID. No file I/O.
     * Replaces resolveTargetTenant() which used to re-open the workbook.
     */
    private Long resolveTargetTenantFromEntityId(String fileEntityId) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        boolean isSuperAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));

        Long sessionTenantId = TenantContext.getCurrentTenant();

        if (fileEntityId == null || fileEntityId.trim().isEmpty()) {
            if (sessionTenantId != null) return sessionTenantId;
            throw new RuntimeException("Could not identify Entity/Tenant from file content (Row 2, Cell 1 missing).");
        }
        fileEntityId = fileEntityId.trim();

        if (isSuperAdmin) {
            String finalFileEntityId = fileEntityId;
            return tenantRepository.findByBankShortCode(fileEntityId)
                    .map(com.acquira.common.model.Tenant::getTenantId)
                    .or(() -> tenantRepository.findByInstitutionId(finalFileEntityId)
                            .map(com.acquira.common.model.Tenant::getTenantId))
                    .orElseThrow(() -> new RuntimeException(
                            "Super Admin Upload: No Tenant found for Entity ID '" + finalFileEntityId
                                    + "' (Checked Short Code & Institution ID)."));
        } else {
            if (sessionTenantId == null) {
                throw new RuntimeException("No session tenant found for user.");
            }
            com.acquira.common.model.Tenant sessionTenant = tenantRepository.findById(sessionTenantId)
                    .orElseThrow(() -> new RuntimeException("Session Tenant not found in DB"));

            boolean match = fileEntityId.equalsIgnoreCase(sessionTenant.getBankShortCode())
                    || fileEntityId.equalsIgnoreCase(sessionTenant.getInstitutionId());

            if (!match) {
                throw new RuntimeException("Permission Denied: You belong to '" + sessionTenant.getBankShortCode()
                        + "' but are trying to upload data for '" + fileEntityId + "'.");
            }

            return sessionTenantId;
        }
    }

    // Kept for processSingleServerFile() which still uses the old API — it now delegates
    // to the cached scan if available, otherwise re-reads. Not in the hot path for UI uploads.
    private Long resolveTargetTenant(String filePath) {
        return resolveTargetTenantFromEntityId(scanFileOnce(filePath).entityId);
    }

    public org.springframework.batch.core.JobExecution processUnifiedFile(MultipartFile file) throws Exception {
        long t0 = System.currentTimeMillis();
        // Save File FIRST so we can read it
        String filePath = saveFile(file);
        Path tempFile = Paths.get(filePath);

        try {
            // PERF FIX: previously called detectFileType() then resolveTargetTenant()
            // — each opens the Excel workbook independently via Apache POI. With cold
            // POI startup + reading the same workbook twice, this added ~3s of unnecessary
            // I/O before the batch job even launched. Now both header-row inspections
            // happen in a single workbook open.
            FileScanResult scan = scanFileOnce(filePath);
            String detectedType = scan.detectedType;
            String fileEntityId = scan.entityId;
            log.info("File scanned in {}ms (type={}, entityId={})",
                System.currentTimeMillis() - t0, detectedType, fileEntityId);

            if ("LEGACY_EXCEL".equals(detectedType)) {
                throw new RuntimeException(
                        "Format Error: You uploaded a Legacy Excel (.xls) file. Please convert it to Modern Excel (.xlsx) for high-performance processing (Rows > 65k).");
            }

            // Resolve Tenant (using already-extracted entity ID — no second file read)
            Long targetTenantId = resolveTargetTenantFromEntityId(fileEntityId);
            String entityName = tenantRepository.findById(targetTenantId).map(t -> t.getInstitutionId()).orElse("Unknown");

            if ("MERCHANT".equals(detectedType)) {
                auditService.log("BATCH_RUN",
                        String.format("Processing MERCHANT file for Tenant: %s (%d)", entityName, targetTenantId));

                JobParameters jobParameters = new JobParametersBuilder()
                        .addLong("tenantId", targetTenantId)
                        .addString("fullPath", filePath)
                        .addLong("startedAt", System.currentTimeMillis())
                        .toJobParameters();
                org.springframework.batch.core.JobExecution execution = jobLauncher.run(merchantMasterJob, jobParameters);

                // ASYNC NOTE: with the async JobLauncher, we cannot delete the temp file here —
                // the batch job has only just been queued; reading the file happens later on a
                // background thread. Deleting now would crash the in-progress job.
                // The @Scheduled cleanupOrphanedTempFiles() task removes files older than 1 hour,
                // which is more than enough for any single batch job to complete.
                return execution;

            } else if ("TRANSACTION".equals(detectedType)) {
                auditService.log("BATCH_RUN",
                        String.format("Processing TRANSACTION file for Tenant: %s (%d)", entityName,
                                targetTenantId));

                JobParameters jobParameters = new JobParametersBuilder()
                        .addLong("tenantId", targetTenantId)
                        .addString("fullPath", filePath)
                        .addLong("startedAt", System.currentTimeMillis())
                        .toJobParameters();
                org.springframework.batch.core.JobExecution execution = jobLauncher.run(transactionLoadJob, jobParameters);

                // PERF FIX: Trigger Reporting Update ASYNCHRONOUSLY.
                //
                // Previously this was a synchronous call which blocked the HTTP response
                // until ManualIngestionService finished processing every distinct payment
                // date in the upload. With 180+ dates that was 2-3 extra seconds of wall
                // time tacked onto the response, pushing some uploads past the browser's
                // HTTP timeout and producing a misleading 500 error in the UI even though
                // the batch job had completed successfully.
                //
                // Now: the batch job's COMPLETED status returns to the UI immediately, and
                // reporting metrics refresh in a background thread. Failures are logged but
                // do not affect the upload's success status (same as before).
                //
                // The captured tenantId/entityName are effectively final and safe to use
                // inside the async lambda.
                final Long asyncTenantId = targetTenantId;
                final String asyncEntityName = entityName;
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        long t = System.currentTimeMillis();
                        manualIngestionService.processManualUpload(asyncTenantId);
                        log.info("[async] Reporting update completed for tenant {} ({}) in {} ms",
                            asyncEntityName, asyncTenantId, System.currentTimeMillis() - t);
                    } catch (Exception e) {
                        log.warn("[async] Reporting update failed for tenant {} ({}): {}",
                            asyncEntityName, asyncTenantId, e.getMessage());
                    }
                });

                // ASYNC NOTE: see merchant branch above — the batch job runs on a background
                // thread now; deleting the temp file here would yank the input out from under it.
                // Cleanup is handled by the @Scheduled cleanupOrphanedTempFiles() task.
                return execution;

            } else {
                throw new RuntimeException("Unknown file format.");
            }
        } catch (Exception e) {
            // On synchronous failure (e.g. wrong tenant, legacy excel) the batch job
            // never started, so it's safe to delete the temp file. We only skip
            // deletion when the job WAS submitted — which can only happen on the
            // success paths above.
            deleteTempFile(tempFile);
            throw e;
        }
    }

    // Kept for backward compatibility
    public org.springframework.batch.core.JobExecution processMerchantFile(MultipartFile file) throws Exception {
        return processUnifiedFile(file);
    }

    public org.springframework.batch.core.JobExecution processTransactionFile(MultipartFile file, String paymentDate)
            throws Exception {
        return processUnifiedFile(file);
    }

    /**
     * Multi-file UPLOAD path (screen → multiple files in one request).
     * Saves every file to disk, classifies each, then runs the SAME pipeline as the
     * server-folder path: merchants first, then transactions, then ONE reporting update
     * per tenant. This keeps screen-upload-multi and server-folder-upload behaviorally
     * identical so users can drop multiple files either way and get the same result.
     *
     * Returns a per-file status map matching the shape of processServerPath().
     */
    public java.util.Map<String, Object> processMultipleUploadedFiles(java.util.List<MultipartFile> files) throws Exception {
        if (files == null || files.isEmpty()) {
            throw new RuntimeException("No files provided");
        }

        // 1. Save every uploaded file to disk and remember the saved path.
        java.util.List<java.io.File> savedFiles = new java.util.ArrayList<>();
        for (MultipartFile mf : files) {
            if (mf == null || mf.isEmpty()) continue;
            String saved = saveFile(mf);
            savedFiles.add(new java.io.File(saved));
        }

        if (savedFiles.isEmpty()) {
            throw new RuntimeException("All uploaded files were empty");
        }

        // 2. Classify and run via the same internal helpers as processServerPath().
        // Reuses processSingleServerFile() so the upload path is byte-for-byte identical to
        // the server-folder path past this point.
        //
        // ASYNC NOTE: with the async JobLauncher, runMultiFileBatch() returns as soon as
        // every job has been submitted — not after they complete. So we cannot delete the
        // temp files in a finally block; the in-flight batch jobs are still reading them.
        // The @Scheduled cleanupOrphanedTempFiles() task removes files older than 1 hour.
        return runMultiFileBatch(savedFiles);
    }

    /**
     * Internal: classify a list of files (already on disk), launch jobs in the right order,
     * run reporting update per tenant. Shared between screen-multi-upload and server-folder.
     */
    private java.util.Map<String, Object> runMultiFileBatch(java.util.List<java.io.File> dataFiles) {
        java.util.List<java.io.File> merchantFiles = new java.util.ArrayList<>();
        java.util.List<java.io.File> transactionFiles = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> skippedFiles = new java.util.ArrayList<>();

        for (java.io.File f : dataFiles) {
            String type = detectFileType(f.getAbsolutePath());
            if ("MERCHANT".equals(type)) {
                merchantFiles.add(f);
            } else if ("TRANSACTION".equals(type)) {
                transactionFiles.add(f);
            } else if ("LEGACY_EXCEL".equals(type)) {
                skippedFiles.add(java.util.Map.of(
                        "file", f.getName(), "reason", "Legacy Excel (.xls) — convert to .xlsx"));
            } else {
                skippedFiles.add(java.util.Map.of(
                        "file", f.getName(), "reason", "Unknown format — could not detect MERCHANT or TRANSACTION headers"));
            }
        }

        log.info("Multi-file batch: {} merchant, {} transaction, {} skipped",
                merchantFiles.size(), transactionFiles.size(), skippedFiles.size());

        java.util.List<java.util.Map<String, Object>> results = new java.util.ArrayList<>();
        java.util.Set<Long> processedTenants = new java.util.LinkedHashSet<>();
        int successCount = 0;
        int failCount = 0;

        // Phase 1: MERCHANT files first
        for (java.io.File f : merchantFiles) {
            java.util.Map<String, Object> r = processSingleServerFile(f, "MERCHANT");
            results.add(r);
            if ("SUCCESS".equals(r.get("status"))) {
                successCount++;
                if (r.get("tenantId") != null) processedTenants.add((Long) r.get("tenantId"));
            } else failCount++;
        }

        // Phase 2: TRANSACTION files
        for (java.io.File f : transactionFiles) {
            java.util.Map<String, Object> r = processSingleServerFile(f, "TRANSACTION");
            results.add(r);
            if ("SUCCESS".equals(r.get("status"))) {
                successCount++;
                if (r.get("tenantId") != null) processedTenants.add((Long) r.get("tenantId"));
            } else failCount++;
        }

        // Phase 3: ONE reporting update per tenant (not per file) — ASYNCHRONOUS.
        //
        // PERF FIX: same reasoning as the single-file TRANSACTION branch above.
        // Reporting metrics no longer block the HTTP response. With multiple tenants
        // this is especially important because reporting time scales with tenant count.
        if (!transactionFiles.isEmpty()) {
            for (Long tenantId : processedTenants) {
                final Long asyncTenantId = tenantId;
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        long t = System.currentTimeMillis();
                        log.info("[async] Running reporting update for tenant {}", asyncTenantId);
                        manualIngestionService.processManualUpload(asyncTenantId);
                        log.info("[async] Reporting update completed for tenant {} in {} ms",
                            asyncTenantId, System.currentTimeMillis() - t);
                    } catch (Exception e) {
                        log.warn("[async] Reporting update failed for tenant {}: {}",
                            asyncTenantId, e.getMessage());
                    }
                });
            }
        }

        java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("totalFiles", dataFiles.size());
        response.put("merchantFiles", merchantFiles.size());
        response.put("transactionFiles", transactionFiles.size());
        response.put("success", successCount);
        response.put("failed", failCount);
        response.put("skipped", skippedFiles);
        response.put("fileResults", results);
        response.put("status", failCount == 0 ? "ALL_SUCCESS" : (successCount > 0 ? "PARTIAL" : "ALL_FAILED"));
        return response;
    }

    /**
     * Process a file OR folder from the server filesystem.
     *
     * If path is a FOLDER:
     *   - Scans for .xlsx, .csv, .tsv, .txt files
     *   - Auto-detects each as MERCHANT or TRANSACTION
     *   - Processes ALL MERCHANT files first (dim tables must exist before transactions)
     *   - Then processes ALL TRANSACTION files
     *   - Runs reporting update once at the end
     *   - Returns summary with per-file results
     *
     * If path is a FILE: processes that single file.
     */
    public java.util.Map<String, Object> processServerPath(String path) throws Exception {
        // P0-4 + P2-6 fix: validate path INSIDE the service so the controller
        // is just a thin dispatcher. Previously the only path check lived in
        // FileUploadController, meaning any other caller of processServerPath()
        // — a scheduled job, a different controller, an MCP integration — could
        // pass arbitrary filesystem paths.
        //
        // Hardened against symlink traversal: a malicious symlink placed inside
        // an allowed dir (e.g. /opt/acquira/data/imports/leak -> /etc) would have
        // passed the old startsWith() check because Paths.normalize() doesn't
        // resolve symlinks. We now use toRealPath() which DOES, and reject
        // anything with a symlink in its path.
        Path safePath = validateAllowedPath(path);

        java.io.File target = safePath.toFile();
        if (!target.exists()) throw new RuntimeException("Path not found: " + path);

        // Collect files to process
        java.util.List<java.io.File> dataFiles = new java.util.ArrayList<>();

        if (target.isDirectory()) {
            java.io.File[] files = target.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".xlsx") || lower.endsWith(".csv")
                        || lower.endsWith(".tsv") || lower.endsWith(".txt");
            });
            if (files != null) {
                java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
                // Per-file symlink check inside the directory — a symlink to
                // /etc/passwd inside an allowed dir is still a leak.
                for (java.io.File f : files) {
                    Path fp = f.toPath();
                    if (Files.isSymbolicLink(fp)) {
                        log.warn("Skipping symlink file in allowed dir: {}", fp);
                        continue;
                    }
                    dataFiles.add(f);
                }
            }
        } else {
            dataFiles.add(target);
        }

        if (dataFiles.isEmpty()) {
            throw new RuntimeException("No data files (.xlsx, .csv, .tsv) found in: " + path);
        }

        // PERF/CONSISTENCY FIX: classification + launch + reporting are now in
        // runMultiFileBatch(), shared with the screen-multi-upload path. Behavior
        // is identical regardless of whether files arrived via screen or server folder.
        java.util.Map<String, Object> response = runMultiFileBatch(dataFiles);

        // Add server-path-specific fields
        java.util.Map<String, Object> wrapped = new java.util.LinkedHashMap<>();
        wrapped.put("path", path);
        wrapped.putAll(response);
        log.info("Server path processing complete: {} success, {} failed, {} skipped",
                response.get("success"), response.get("failed"),
                ((java.util.List<?>) response.get("skipped")).size());
        return wrapped;
    }

    /**
     * P0-4 fix: resolve the supplied path to a real filesystem location and
     * confirm it lives under one of the configured allowed roots, with no
     * symlinks in the chain. Returns the resolved canonical path on success,
     * throws SecurityException with a generic message otherwise (don't leak
     * filesystem layout to a caller probing for paths).
     */
    private Path validateAllowedPath(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new SecurityException("Path is required");
        }
        // Defense in depth: reject obvious traversal attempts pre-resolution.
        // toRealPath() will catch the rest, but this gives a cleaner error.
        if (requestedPath.contains("..")) {
            throw new SecurityException("Path traversal not allowed");
        }

        Path requested = Paths.get(requestedPath);
        Path resolved;
        try {
            // toRealPath() FOLLOWS symlinks AND resolves to canonical form.
            // If anything in the chain is a symlink to outside an allowed root,
            // the resolved path will reflect the real target and the prefix
            // check below will reject it.
            resolved = requested.toRealPath();
        } catch (IOException e) {
            throw new SecurityException("Path could not be resolved");
        }

        // Reject if the leaf itself is a symlink (toRealPath() resolved through it,
        // but we still want to flag this defensively for audit purposes).
        if (Files.isSymbolicLink(requested)) {
            log.warn("Rejected symlink path: {}", requested);
            throw new SecurityException("Symlinks are not permitted");
        }

        String[] prefixes = allowedPathsCsv.split(",");
        for (String prefix : prefixes) {
            String trimmed = prefix.trim();
            if (trimmed.isEmpty()) continue;
            Path prefixPath;
            try {
                prefixPath = Paths.get(trimmed).toRealPath(LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                // The configured prefix dir doesn't exist on this host — skip it,
                // try the next. (Common in dev: prod prefix /opt/acquira/data
                // doesn't exist, only data/uploads does.)
                continue;
            }
            if (resolved.startsWith(prefixPath)) {
                return resolved;
            }
        }

        log.warn("Rejected upload path — not under allowed roots. requested={}, resolved={}",
                requestedPath, resolved);
        throw new SecurityException("Path is not within an allowed data directory");
    }

    /**
     * Process a single server file with known type.
     * Returns a result map with status, jobId, file details.
     */
    private java.util.Map<String, Object> processSingleServerFile(java.io.File file, String fileType) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("file", file.getName());
        result.put("type", fileType);
        result.put("sizeMB", file.length() / (1024 * 1024));

        try {
            String filePath = file.getAbsolutePath();

            // Resolve Tenant
            Long targetTenantId = resolveTargetTenant(filePath);
            String entityName = tenantRepository.findById(targetTenantId)
                    .map(t -> t.getInstitutionId()).orElse("Unknown");

            result.put("tenantId", targetTenantId);
            result.put("entity", entityName);

            auditService.log("BATCH_RUN",
                    String.format("Processing %s file (server): %s for Tenant: %s (%d) — %d MB",
                            fileType, file.getName(), entityName, targetTenantId,
                            file.length() / (1024 * 1024)));

            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("tenantId", targetTenantId)
                    .addString("fullPath", filePath)
                    .addLong("startedAt", System.currentTimeMillis())
                    .toJobParameters();

            org.springframework.batch.core.JobExecution execution;
            if ("MERCHANT".equals(fileType)) {
                execution = jobLauncher.run(merchantMasterJob, jobParameters);
            } else {
                execution = jobLauncher.run(transactionLoadJob, jobParameters);
            }

            result.put("jobId", execution.getId());
            result.put("jobStatus", execution.getStatus().toString());
            result.put("status", "SUCCESS");

            log.info("{} file submitted to batch: {} — Job {} — {} (status will update asynchronously; poll /api/batch/jobs/{}/status)",
                    fileType, file.getName(), execution.getId(), execution.getStatus(), execution.getId());

        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            log.error("Failed to process {} file: {} — {}", fileType, file.getName(), e.getMessage());
        }

        return result;
    }

    private String detectFileType(String filePath) {
        String lowerPath = filePath.toLowerCase();

        // CSV/TSV/TXT files: read header line directly
        if (lowerPath.endsWith(".csv") || lowerPath.endsWith(".tsv") || lowerPath.endsWith(".txt")) {
            return detectFileTypeFromCsvHeader(filePath);
        }

        // Excel files
        try (InputStream is = new FileInputStream(filePath);
                Workbook workbook = WorkbookFactory.create(is)) {

            if (workbook instanceof org.apache.poi.hssf.usermodel.HSSFWorkbook) {
                return "LEGACY_EXCEL";
            }

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() > 0) {
                Row row = sheet.getRow(0);
                if (row != null) {
                    boolean hasTransactionId = false;
                    boolean hasMerchantMarker = false;

                    for (Cell cell : row) {
                        String header = getCellValue(cell);
                        if (header != null) {
                            String h = header.trim().toLowerCase();
                            if (h.contains("transaction id") || h.contains("txn id") || h.equals("ref number") ||
                                    h.contains("transaction date") || h.contains("payment date") || h.contains("arn") ||
                                    h.contains("rrn") || h.contains("txn currency")) {
                                hasTransactionId = true;
                            }
                            if (h.contains("merchant name") || h.contains("merchant id") || h.equals("mid")) {
                                hasMerchantMarker = true;
                            }
                        }
                    }
                    if (hasTransactionId)
                        return "TRANSACTION";
                    if (hasMerchantMarker)
                        return "MERCHANT";
                }
            }
        } catch (org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
                | org.apache.poi.poifs.filesystem.OfficeXmlFileException e) {
            // Not Excel — try as CSV
            return detectFileTypeFromCsvHeader(filePath);
        } catch (Exception e) {
            log.warn("Error detecting file type: {}", e.getMessage());
        }
        return "UNKNOWN";
    }

    private String detectFileTypeFromCsvHeader(String filePath) {
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String headerLine = br.readLine();
            if (headerLine != null) {
                String h = headerLine.toLowerCase();
                if (h.contains("transaction id") || h.contains("txn id") || h.contains("ref number") ||
                        h.contains("transaction date") || h.contains("payment date") || h.contains("arn") ||
                        h.contains("rrn") || h.contains("txn currency")) {
                    return "TRANSACTION";
                }
                if (h.contains("merchant name") || h.contains("merchant id") || h.contains("mid")) {
                    return "MERCHANT";
                }
            }
        } catch (Exception e) {
            log.warn("Error reading CSV header: {}", e.getMessage());
        }
        return "UNKNOWN";
    }

    private String extractPaymentDate(String filePath) {
        // User Requirement: strict reliance on file content (Payment Date column)
        // Do NOT use filename.

        try (InputStream is = new FileInputStream(filePath);
                Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            int paymentDateColIdx = -1;

            // Header Row
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    String h = getCellValue(cell);
                    if (h != null && (h.trim().equalsIgnoreCase("Payment Date")
                            || h.trim().equalsIgnoreCase("PaymentDate")
                            || h.trim().equalsIgnoreCase("Date"))) {
                        paymentDateColIdx = cell.getColumnIndex();
                        break;
                    }
                }
            }

            // Data Row
            if (paymentDateColIdx != -1) {
                Row dataRow = sheet.getRow(1);
                if (dataRow != null) {
                    Cell cell = dataRow.getCell(paymentDateColIdx);
                    // Handle Excel Numeric Date
                    if (cell.getCellType() == CellType.NUMERIC
                            && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                        return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    }

                    String val = getCellValue(cell);
                    if (val != null && !val.trim().isEmpty()) {
                        try {
                            // Try parsing standard formats
                            return java.time.LocalDate.parse(val.trim()).toString();
                        } catch (Exception e) {
                            // Try other formats? For now, just strict ISO or Excel numeric
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error extracting payment date: " + e.getMessage());
        }
        return null;
    }

    private String saveFile(MultipartFile file) throws IOException {
        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path path = Paths.get(UPLOAD_DIR + fileName);
        Files.copy(file.getInputStream(), path);
        log.info("Saved temp upload: {} ({} KB)", path.getFileName(), file.getSize() / 1024);
        return path.toAbsolutePath().toString();
    }

    /**
     * Delete a temp file after batch processing completes.
     * Logs success/failure but never throws.
     */
    private void deleteTempFile(Path file) {
        try {
            if (file != null && Files.exists(file)) {
                long sizeKb = Files.size(file) / 1024;
                Files.delete(file);
                log.info("Cleaned up temp file: {} ({} KB)", file.getFileName(), sizeKb);
            }
        } catch (IOException e) {
            log.warn("Could not delete temp file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Scheduled safety-net cleanup: delete any orphaned temp files older than 1 hour.
     * Runs every 30 minutes. Catches files that slipped through normal cleanup
     * (e.g. JVM crash during processing).
     */
    @Scheduled(fixedRate = 30 * 60 * 1000) // every 30 minutes
    public void cleanupOrphanedTempFiles() {
        Path uploadDir = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadDir)) return;

        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int deleted = 0;
        long freedBytes = 0;

        try (Stream<Path> files = Files.list(uploadDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                try {
                    if (Files.isRegularFile(file)
                            && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        long size = Files.size(file);
                        Files.delete(file);
                        deleted++;
                        freedBytes += size;
                    }
                } catch (IOException e) {
                    log.warn("Cleanup: could not delete {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Cleanup: could not list upload dir: {}", e.getMessage());
        }

        if (deleted > 0) {
            log.info("Temp file cleanup: deleted {} orphaned files, freed {} KB",
                    deleted, freedBytes / 1024);
        }
    }
}
