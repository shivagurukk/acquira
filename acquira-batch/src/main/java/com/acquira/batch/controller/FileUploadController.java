package com.acquira.batch.controller;

import com.acquira.batch.service.FileUploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

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
     *   POST /api/upload/process-server-file?path=/opt/acquira/data/uploads/
     *   POST /api/upload/process-server-file?path=/opt/acquira/data/uploads/transactions.xlsx
     */
    @PostMapping("/process-server-file")
    public ResponseEntity<?> processServerFile(@RequestParam("path") String filePath) {
        try {
            java.io.File target = new java.io.File(filePath);
            if (!target.exists()) {
                return ResponseEntity.badRequest().body(
                    java.util.Map.of("error", "Path not found: " + filePath));
            }

            java.util.Map<String, Object> result = fileUploadService.processServerPath(filePath);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
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
