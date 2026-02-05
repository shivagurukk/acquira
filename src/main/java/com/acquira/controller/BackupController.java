package com.acquira.controller;

import com.acquira.service.BackupService;
import com.acquira.service.BackupService.BackupFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/backups")
@PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')") // Ensure only Admins can access
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping
    public List<BackupFile> listBackups() {
        return backupService.listBackups();
    }

    @PostMapping("/create")
    public ResponseEntity<?> createBackup() {
        try {
            String fileName = backupService.createBackup();
            return ResponseEntity.ok(Map.of("message", "Backup created successfully", "fileName", fileName));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/restore/{fileName}")
    public ResponseEntity<?> restoreBackup(@PathVariable String fileName) {
        try {
            backupService.restoreBackup(fileName);
            return ResponseEntity.ok(Map.of("message", "Database restored successfully from " + fileName));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{fileName}")
    public ResponseEntity<?> deleteBackup(@PathVariable String fileName) {
        try {
            backupService.deleteBackup(fileName);
            return ResponseEntity.ok(Map.of("message", "Backup deleted successfully"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String fileName) {
        java.io.File file = backupService.getBackupFile(fileName);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .body(resource);
    }
}
