package com.acquira.batch.controller;

import com.acquira.batch.service.FileUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    private final FileUploadService fileUploadService;

    public FileUploadController(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @PostMapping("/merchant")
    public ResponseEntity<?> uploadMerchantFile(@RequestParam("file") MultipartFile file) {
        try {
            org.springframework.batch.core.JobExecution execution = fileUploadService.processMerchantFile(file);
            return ResponseEntity.ok(mapJobExecution(execution, "Merchant file processing started"));
        } catch (Exception e) {
            log.error("Merchant file upload failed", e);
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
            log.error("Transaction file upload failed", e);
            return ResponseEntity.internalServerError().body("Error processing file: " + e.getMessage());
        }
    }

    @PostMapping("") // Maps to /api/upload
    public ResponseEntity<?> uploadUnifiedFile(@RequestParam("file") MultipartFile file) {
        try {
            org.springframework.batch.core.JobExecution execution = fileUploadService.processUnifiedFile(file);
            return ResponseEntity.ok(mapJobExecution(execution, "File processing started"));
        } catch (Exception e) {
            log.error("Unified file upload failed", e);
            return ResponseEntity.badRequest().body("Error processing file: " + e.getMessage());
        }
    }

    /**
     * Multi-file upload endpoint: accepts an array of MultipartFiles in one request.
     * Files are auto-classified (MERCHANT vs TRANSACTION) and processed in the right order:
     *   1. all MERCHANT files first (so dim tables exist for transaction joins)
     *   2. then all TRANSACTION files
     *   3. then ONE reporting update per tenant
     *
     * This is byte-for-byte the same logic the server-folder endpoint runs, so users
     * can drop multiple files via the screen and get identical behavior to dropping
     * them in a server folder.
     *
     * Usage from a multipart form: multiple <input name="files"> fields, or
     *   curl -F "files=@a.xlsx" -F "files=@b.csv" -F "files=@c.xlsx" /api/upload/multi
     */
    @PostMapping("/multi")
    public ResponseEntity<?> uploadMultipleFiles(@RequestParam("files") java.util.List<MultipartFile> files) {
        try {
            java.util.Map<String, Object> result = fileUploadService.processMultipleUploadedFiles(files);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Multi-file upload failed", e);
            return ResponseEntity.badRequest().body(
                java.util.Map.of("error", "Error processing files: " + e.getMessage()));
        }
    }

    /**
     * Process file(s) from the server filesystem (skip HTTP upload).
     * Accepts a file path OR a folder path.
     *
     * P0-4 + P2-6 fix: path validation is now authoritative inside
     * FileUploadService.processServerPath(). The controller is a thin
     * dispatcher that translates a SecurityException into HTTP 403.
     *
     * Usage:
     *   POST /api/upload/process-server-file?path=/opt/acquira/data/imports/
     *   POST /api/upload/process-server-file?path=/opt/acquira/data/imports/transactions.xlsx
     */
    @PostMapping("/process-server-file")
    public ResponseEntity<?> processServerFile(@RequestParam("path") String filePath) {
        try {
            java.util.Map<String, Object> result = fileUploadService.processServerPath(filePath);
            return ResponseEntity.ok(result);
        } catch (SecurityException sec) {
            // Generic 403 — don't echo the requested path or any internal detail.
            return ResponseEntity.status(403).body(
                java.util.Map.of("error", sec.getMessage()));
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
