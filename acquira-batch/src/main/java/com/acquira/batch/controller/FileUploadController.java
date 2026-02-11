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

    private java.util.Map<String, Object> mapJobExecution(org.springframework.batch.core.JobExecution execution,
            String message) {
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("jobId", execution.getId());
        response.put("status", execution.getStatus().toString());
        response.put("message", message);
        return response;
    }
}
