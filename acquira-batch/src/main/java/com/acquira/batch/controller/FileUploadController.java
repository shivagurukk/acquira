package com.acquira.batch.controller;

import com.acquira.batch.service.FileUploadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    private final FileUploadService fileUploadService;

    /**
     * Allowed directories for server-side file processing.
     * Configurable via application property: app.upload.allowed-paths
     *
     * Defaults to: /opt/acquira/data, data/uploads, data/imports
     *
     * Override in application-prod.properties:
     *   app.upload.allowed-paths=/opt/acquira/data,/mnt/nfs/acquira-data,/home/acquira/drops
     */
    @Value("${app.upload.allowed-paths:/opt/acquira/data,data/uploads,data/imports}")
    private String allowedPathsCsv;

    public FileUploadController(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @PostMapping("/merchant")
    public ResponseEntity<?> uploadMerchantFile(@RequestParam("file") MultipartFile file) {
        try {
            org.springframework.batch.core.JobExecution execution = fileUploadService.processMerchantFile(file);
            return ResponseEntity.ok(mapJobExecution(execution, "Merchant file processing started"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error processing file: " + e.getMessage());
        }
    }

    @PostMapping("/transaction")
    public ResponseEntity<?> uploadTransactionFile(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "paymentDate", required = false) String paymentDate) {
        try {
            if (paymentDate == null) {
                paymentDate = java.time.LocalDate.now().toString();
            }
            org.springframework.batch.core.JobExecution execution = fileUploadService.processTransactionFile(file,
                    paymentDate);
            return ResponseEntity.ok(mapJobExecution(execution, "Transaction file processing started"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error processing file: " + e.getMessage());
        }
    }

    @PostMapping("") // Maps to /api/upload
    public ResponseEntity<?> uploadUnifiedFile(@RequestParam("file") MultipartFile file) {
        try {
            org.springframework.batch.core.JobExecution execution = fileUploadService.processUnifiedFile(file);
            return ResponseEntity.ok(mapJobExecution(execution, "File processing started"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Error processing file: " + e.getMessage());
        }
    }

    /**
     * Process file(s) from the server filesystem (skip HTTP upload).
     * Accepts a file path OR a folder path.
     *
     * If path is a FOLDER:
     *   - Scans for .xlsx, .csv, .tsv files
     *   - Auto-detects each as MERCHANT or TRANSACTION
     *   - Processes MERCHANT files first (so dim tables exist for transactions)
     *   - Then processes TRANSACTION files sequentially
     *   - Returns summary of all jobs
     *
     * If path is a FILE: processes that single file.
     *
     * Usage:
     *   POST /api/upload/process-server-file?path=/opt/acquira/data/imports/
     *   POST /api/upload/process-server-file?path=/opt/acquira/data/imports/transactions.xlsx
     */
    @PostMapping("/process-server-file")
    public ResponseEntity<?> processServerFile(@RequestParam("path") String filePath) {
        try {
            // Block path traversal BEFORE normalization — normalization collapses ".." silently
            if (filePath.contains("..")) {
                return ResponseEntity.status(403).body(
                    java.util.Map.of("error", "Path traversal not allowed"));
            }

            // Security: normalize user-supplied path and check against allowed directories
            java.nio.file.Path normalized = java.nio.file.Paths.get(filePath).normalize().toAbsolutePath();

            // Split the configured CSV into prefixes and normalize each one
            String[] prefixes = allowedPathsCsv.split(",");
            boolean allowed = false;
            for (String prefix : prefixes) {
                String trimmed = prefix.trim();
                if (trimmed.isEmpty()) continue;
                java.nio.file.Path prefixPath = java.nio.file.Paths.get(trimmed).normalize().toAbsolutePath();
                if (normalized.startsWith(prefixPath)) {
                    allowed = true;
                    break;
                }
            }

            if (!allowed) {
                return ResponseEntity.status(403).body(java.util.Map.of(
                    "error", "Access denied: path must be within allowed data directories",
                    "requestedPath", normalized.toString(),
                    "allowedPrefixes", allowedPathsCsv));
            }

            java.io.File target = new java.io.File(filePath);
            if (!target.exists()) {
                return ResponseEntity.badRequest().body(
                    java.util.Map.of("error", "Path not found: " + filePath));
            }

            java.util.Map<String, Object> result = fileUploadService.processServerPath(filePath);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                java.util.Map.of("error", e.getMessage()));
        }
    }

    private java.util.Map<String, Object> mapJobExecution(org.springframework.batch.core.JobExecution execution,
            String message) {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("jobId", execution.getId());
        response.put("status", execution.getStatus().toString());
        response.put("message", message);
        return response;
    }
}
