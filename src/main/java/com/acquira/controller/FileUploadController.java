package com.acquira.controller;

import com.acquira.service.FileUploadService;
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

    private void validateFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new IllegalArgumentException("File must have a name");
        }
        String lower = filename.toLowerCase();
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new IllegalArgumentException("Invalid file type. Only .csv, .xlsx, .xls are allowed.");
        }
    }

    @PostMapping("/merchant")
    public ResponseEntity<?> uploadMerchantFile(@RequestParam("file") MultipartFile file) {
        try {
            validateFile(file);
            org.springframework.batch.core.JobExecution execution = fileUploadService.processMerchantFile(file);
            return ResponseEntity.ok(mapJobExecution(execution, "Merchant file processing started"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error processing file: " + e.getMessage());
        }
    }

    @PostMapping("/transaction")
    public ResponseEntity<?> uploadTransactionFile(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "paymentDate", required = false) String paymentDate) {
        try {
            validateFile(file);
            if (paymentDate == null) {
                paymentDate = java.time.LocalDate.now().toString();
            }
            org.springframework.batch.core.JobExecution execution = fileUploadService.processTransactionFile(file,
                    paymentDate);
            return ResponseEntity.ok(mapJobExecution(execution, "Transaction file processing started"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error processing file: " + e.getMessage());
        }
    }

    @PostMapping("") // Maps to /api/upload
    public ResponseEntity<?> uploadUnifiedFile(@RequestParam("file") MultipartFile file) {
        try {
            validateFile(file);
            org.springframework.batch.core.JobExecution execution = fileUploadService.processUnifiedFile(file);
            return ResponseEntity.ok(mapJobExecution(execution, "File processing started"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Error processing file: " + e.getMessage());
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
