package com.acquira.batch.controller;

import com.acquira.batch.service.BackupService;
import com.acquira.batch.service.BackupService.BackupFile;
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
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/backups")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BackupController {

    private final BackupService backupService;

    // SECURITY FIX: Whitelist pattern for backup filenames — prevents path traversal
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9_.-]+\\.(sql|dump)$");

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
            // Super-admin-only endpoint: surface the real pg_dump reason so failures are diagnosable.
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Backup creation failed",
                    "detail", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/restore/{fileName}")
    public ResponseEntity<?> restoreBackup(@PathVariable String fileName) {
        if (!isValidFileName(fileName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid filename"));
        }
        try {
            backupService.restoreBackup(fileName);
            return ResponseEntity.ok(Map.of("message", "Database restored successfully from " + fileName));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Restore failed",
                    "detail", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping("/{fileName}")
    public ResponseEntity<?> deleteBackup(@PathVariable String fileName) {
        if (!isValidFileName(fileName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid filename"));
        }
        try {
            backupService.deleteBackup(fileName);
            return ResponseEntity.ok(Map.of("message", "Backup deleted successfully"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Delete failed"));
        }
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String fileName) {
        if (!isValidFileName(fileName)) {
            return ResponseEntity.badRequest().build();
        }

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

    /**
     * SECURITY FIX: Validates filename against whitelist to prevent path traversal.
     * Rejects: ../../etc/passwd, ../malicious.sql, etc.
     */
    private boolean isValidFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) return false;
        return SAFE_FILENAME.matcher(fileName).matches();
    }
}
